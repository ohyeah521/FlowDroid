package soot.jimple.infoflow.data.accessPaths;

import java.util.Objects;

import soot.Unit;

/**
 * A concolic unit can either represent a normal unit or refer to any unit
 * (abstracted with a symbol)
 * 
 * @author Steven Arzt
 *
 */
public class ConcolicUnit {

	protected final Unit unit;

	/**
	 * Instantiates a new {@link ConcolicUnit} with a concrete unit
	 * 
	 * @param unit The concrete unit
	 */
	public ConcolicUnit(Unit unit) {
		this.unit = unit;
	}

	/**
	 * Instantiates a new {@link ConcolicUnit} as symbolic
	 */
	public ConcolicUnit() {
		this.unit = null;
	}

	/**
	 * Checks whether this unit is symbolic, i.e., can refer to any unit
	 * 
	 * @return True if this unit is a symbol that could represent any unit, false
	 *         otherwise
	 */
	public boolean isSymbolic() {
		return this.unit == null;
	}

	/**
	 * Checks whether this unit is concrete, i.e., refers to a specific unit
	 * 
	 * @return True if this unit represents a specific concrete unit, false
	 *         otherwise
	 */
	public boolean isConcrete() {
		return unit != null;
	}

	/**
	 * Gets the concrete unit that this object refers to. If this object is
	 * symbolic, <code>null</code> is returned
	 * 
	 * @return The concrete unit to which this object refers if it is concrete, or
	 *         <code>null</code> if this object is symbolic
	 */
	public Unit getUnit() {
		return this.unit;
	}

	@Override
	public String toString() {
		return isConcrete() ? unit.toString() : "<symb>";
	}

	@Override
	public int hashCode() {
		return Objects.hash(unit);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ConcolicUnit other = (ConcolicUnit) obj;
		return Objects.equals(unit, other.unit);
	}

}
