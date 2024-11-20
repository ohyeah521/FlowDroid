package soot.jimple.infoflow.solver;

import java.util.Objects;

import soot.Unit;
import soot.jimple.infoflow.data.Abstraction;

/**
 * A data class for end summaries that are computed once a method has been
 * processed so that the data flows through that method can be re-used
 * 
 * @author Steven Arzt
 */
public class EndSummary {

	/**
	 * The exit point of the callee to which the summary applies
	 */
	public Unit eP;

	/**
	 * The taint abstraction at eP
	 */
	public Abstraction d4;

	/**
	 * The abstraction at the beginning of the callee
	 */
	public Abstraction calleeD1;

	public EndSummary(Unit eP, Abstraction d4, Abstraction calleeD1) {
		this.eP = eP;
		this.d4 = d4;
		this.calleeD1 = calleeD1;
	}

	@Override
	public int hashCode() {
		return Objects.hash(calleeD1, d4, eP);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		EndSummary other = (EndSummary) obj;
		return Objects.equals(calleeD1, other.calleeD1) && Objects.equals(d4, other.d4) && Objects.equals(eP, other.eP);
	}

}
