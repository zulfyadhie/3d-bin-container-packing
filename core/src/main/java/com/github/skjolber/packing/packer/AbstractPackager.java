package com.github.skjolber.packing.packer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.skjolber.packing.api.Container;
import com.github.skjolber.packing.api.ContainerItem;
import com.github.skjolber.packing.api.PackResult;
import com.github.skjolber.packing.api.PackResultComparator;
import com.github.skjolber.packing.api.Packager;
import com.github.skjolber.packing.api.PackagerResultBuilder;
import com.github.skjolber.packing.api.Stackable;
import com.github.skjolber.packing.api.StackableItem;
import com.github.skjolber.packing.deadline.PackagerInterruptSupplier;
import com.github.skjolber.packing.deadline.PackagerInterruptSupplierBuilder;
import com.github.skjolber.packing.iterator.BinarySearchIterator;

/**
 * Fit boxes into container, i.e. perform bin packing to a single container.
 *
 * Thread-safe implementation.
 */

public abstract class AbstractPackager<P extends PackResult, B extends PackagerResultBuilder<B>> implements Packager<B> {

	protected static final EmptyPackResult EMPTY_PACK_RESULT = EmptyPackResult.EMPTY;

	protected final PackResultComparator packResultComparator;

	/** limit the number of calls to get System.currentTimeMillis() */
	protected final int checkpointsPerDeadlineCheck;

	/**
	 * Constructor
	 *
	 * @param checkpointsPerDeadlineCheck number of deadline checks to skip, before checking again
	 */

	public AbstractPackager(int checkpointsPerDeadlineCheck, PackResultComparator packResultComparator) {
		this.checkpointsPerDeadlineCheck = checkpointsPerDeadlineCheck;
		this.packResultComparator = packResultComparator;
	}

	protected P pack(List<Integer> containerItemIndexes, PackagerAdapter<P> adapter, PackagerInterruptSupplier interrupt) {
		if(containerItemIndexes.size() <= 2) {
			for (int i = 0; i < containerItemIndexes.size(); i++) {
				if(interrupt.getAsBoolean()) {
					break;
				}

				Integer containerItemIndex = containerItemIndexes.get(i);

				P result = adapter.attempt(containerItemIndex, null);
				if(result == null) {
					return null; // timeout, no result
				}
				if(result.containsLastStackable()) {
					return result;
				}
			}
		} else {
			// perform a binary search among the available containers
			// the list is ranked from most desirable to least.
			PackResult[] results = new PackResult[containerItemIndexes.size()];
			boolean[] checked = new boolean[results.length];

			BinarySearchIterator iterator = new BinarySearchIterator();

			search: do {
				iterator.reset(containerItemIndexes.size() - 1, 0);

				P bestResult = null;
				int bestIndex = Integer.MAX_VALUE;

				do {
					int next = iterator.next();
					int mid = containerItemIndexes.get(next);

					P result = adapter.attempt(mid, bestResult);
					if(result == null) {
						// timeout 
						// return best result so far, whatever it is
						break search;
					}
					checked[mid] = true;
					if(result.containsLastStackable()) {
						results[mid] = result;

						iterator.lower();

						if(mid < bestIndex) {
							bestIndex = mid;
							bestResult = result;
						}
					} else {
						iterator.higher();
					}
					if(interrupt.getAsBoolean()) {
						break search;
					}
				} while (iterator.hasNext());

				// halt when we have a result, and checked all containers at the lower indexes
				for (int i = 0; i < containerItemIndexes.size(); i++) {
					Integer integer = containerItemIndexes.get(i);
					if(results[integer] != null) {
						// remove end items; we already have a better match
						while (containerItemIndexes.size() > i) {
							containerItemIndexes.remove(containerItemIndexes.size() - 1);
						}
						break;
					}

					// remove item
					if(checked[integer]) {
						containerItemIndexes.remove(i);
						i--;
					}
				}
			} while (!containerItemIndexes.isEmpty());

			// return the best, if any
			for (final PackResult result : results) {
				if(result != null) {
					return (P)result;
				}
			}
		}
		return null;
	}

	public List<Container> packList(List<StackableItem> products, List<ContainerItem> containers, int limit) {
		return pack(products, containers, limit, PackagerInterruptSupplierBuilder.NOOP);
	}

	/**
	 * Return a list of containers which holds all the boxes in the argument
	 *
	 * @param boxes     list of boxes to fit in a container
	 * @param limit     maximum number of containers
	 * @param interrupt When true, the computation is interrupted as soon as possible.
	 * @return list of containers, or null if the deadline was reached, or empty list if the packages could not be packaged within the available containers and/or limit.
	 */

	public List<Container> pack(List<StackableItem> boxes, List<ContainerItem> containerItems, int limit, PackagerInterruptSupplier interrupt) {
		PackagerAdapter<P> adapter = adapter(boxes, containerItems, interrupt);

		List<Container> containerPackResults = new ArrayList<>();

		do {
			// is it possible to fit the remaining boxes a single container?
			int maxContainers = limit - containerPackResults.size();
			List<Integer> containerItemIndexes = adapter.getContainers(1);
			if(!containerItemIndexes.isEmpty()) {

				P result = pack(containerItemIndexes, adapter, interrupt);
				if(result != null) {
					containerPackResults.add(adapter.accept(result));

					// positive result
					return containerPackResults;
				}
			}

			// one or more containers
			containerItemIndexes = adapter.getContainers(maxContainers);
			if(containerItemIndexes.isEmpty()) {
				return Collections.emptyList();
			}

			// the best container is the one which can hold the most stackables
			// assume larger boxes is at the end of list, so start there
			P best = null;
			for (int i = containerItemIndexes.size() - 1; i >= 0; i--) {
				if(interrupt.getAsBoolean()) {
					return null;
				}

				Integer containerItemIndex = containerItemIndexes.get(i);

				// can this container hold more than the previously best result?
				if(best != null) {
					ContainerItem containerItem = containerItems.get(containerItemIndex);
					Container container = containerItem.getContainer();

					long loadVolume = best.getLoadVolume();
					if(loadVolume > container.getMaxLoadVolume()) {
						continue;
					}
					int loadWeight = best.getLoadWeight();
					if(loadWeight > container.getMaxLoadWeight()) {
						continue;
					}
				}

				P result = adapter.attempt(containerItemIndex, best);
				if(result == null) {
					// timeout, unless already have a result ready
					if(best != null && best.containsLastStackable()) {
						containerPackResults.add(adapter.accept(best));

						return containerPackResults;
					}

					return null;
				}

				if(!result.isEmpty()) {
					if(best == null || packResultComparator.compare(best, result) != PackResultComparator.ARGUMENT_1_IS_BETTER) {
						best = result;
					}
				}
			}

			if(best == null) {
				// negative result
				return Collections.emptyList();
			}

			containerPackResults.add(adapter.accept(best));
		} while (containerPackResults.size() < limit);

		return null;
	}

	protected abstract PackagerAdapter<P> adapter(List<StackableItem> boxes, List<ContainerItem> containers, PackagerInterruptSupplier interrupt);

	protected long getMinStackableItemVolume(List<StackableItem> stackables) {
		long minVolume = Integer.MAX_VALUE;
		for (StackableItem stackableItem : stackables) {
			Stackable stackable = stackableItem.getStackable();
			if(stackable.getVolume() < minVolume) {
				minVolume = stackable.getVolume();
			}
		}
		return minVolume;
	}

	protected long getMinStackableItemArea(List<StackableItem> stackables) {
		long minArea = Integer.MAX_VALUE;
		for (StackableItem stackableItem : stackables) {
			Stackable stackable = stackableItem.getStackable();
			if(stackable.getMinimumArea() < minArea) {
				minArea = stackable.getMinimumArea();
			}
		}
		return minArea;
	}

	protected long getMinStackableVolume(List<Stackable> stackables) {
		long minVolume = Integer.MAX_VALUE;
		for (Stackable stackable : stackables) {
			if(stackable.getVolume() < minVolume) {
				minVolume = stackable.getVolume();
			}
		}
		return minVolume;
	}

	protected long getMinStackableArea(List<Stackable> stackables) {
		long minArea = Integer.MAX_VALUE;
		for (Stackable stackable : stackables) {
			if(stackable.getMinimumArea() < minArea) {
				minArea = stackable.getMinimumArea();
			}
		}
		return minArea;
	}

}
