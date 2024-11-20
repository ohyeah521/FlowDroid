package soot.jimple.infoflow.solver.fastSolver;

import soot.Unit;
import soot.jimple.infoflow.data.Abstraction;

/**
 * Common interface for all edge scheduling strategies in the solver
 * 
 * @author Steven Arzt
 *
 */
public interface ISchedulingStrategy {

	public void propagateInitialSeeds(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
			boolean isUnbalancedReturn);

	public void propagateNormalFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
			boolean isUnbalancedReturn);

	public void propagateCallFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
			boolean isUnbalancedReturn);

	public void propagateCallToReturnFlow(Abstraction sourceVal, Unit target, Abstraction targetVal,
			Unit relatedCallSite, boolean isUnbalancedReturn);

	public void propagateReturnFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
			boolean isUnbalancedReturn);

}
