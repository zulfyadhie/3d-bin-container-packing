package com.github.skjolber.packing.ep.points3d;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.github.skjolber.packing.api.Placement3D;
import com.github.skjolber.packing.api.ep.Point3D;

/**
 * 
 * Custom list for working with points.
 * 
 */

@SuppressWarnings("unchecked")
public class Point3DArray<P extends Placement3D> {

	private int size = 0;
	private Point3D<P>[] points = new Point3D[16];

	public void ensureAdditionalCapacity(int count) {
		ensureCapacity(size + count);
	}

	public void ensureCapacity(int size) {
		if(points.length < size) {
			Point3D<P>[] nextPoints = new Point3D[size];
			System.arraycopy(this.points, 0, nextPoints, 0, this.points.length);
			this.points = nextPoints;
		}
	}

	public void set(Point3D<P> point, int index) {
		points[index] = point;
		size++;
	}

	public void clear(int index) {
		points[index] = null;
		size--;
	}

	public int size() {
		return size;
	}

	public void reset() {
		Arrays.fill(this.points, 0, points.length, null);
		size = 0;
	}

	public Point3D<P> get(int i) {
		return points[i];
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public void clear() {
		size = 0;
	}

	/**
	 * Returns the hash code value for this list.
	 *
	 * <p>
	 * This implementation uses exactly the code that is used to define the
	 * list hash function in the documentation for the {@link List#hashCode}
	 * method.
	 *
	 * @return the hash code value for this list
	 */
	public int hashCode() {
		int hashCode = 1;
		for (int i = 0; i < size; i++) {
			hashCode = 31 * hashCode + points[i].hashCode();
		}
		return hashCode;
	}

	@Override
	public boolean equals(Object obj) {
		if(obj instanceof Point3DList) {
			Point3DList<P> other = (Point3DList<P>)obj;
			if(other.size() == size) {
				for (int i = 0; i < size; i++) {
					if(!points[i].equals(other.get(i))) {
						return false;
					}
				}
			}
			return true;
		}
		return super.equals(obj);
	}

	public Point3D<P>[] getPoints() {
		return points;
	}

	public void sort(Comparator<Point3D<?>> comparator) {
		Arrays.sort(points, 0, size, comparator);
	}

}
