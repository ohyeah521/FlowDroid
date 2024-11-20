package soot.jimple.infoflow.solver.fastSolver;

import soot.Unit;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.solver.AbstractIFDSSolver;
import soot.jimple.infoflow.solver.fastSolver.IFDSSolver.ScheduleTarget;

/**
 * Default implementations for scheduling strategies
 * 
 * @author Steven Arzt
 *
 */
public class DefaultSchedulingStrategy {

	protected final AbstractIFDSSolver solver;

	/**
	 * Strategy that schedules each edge individually, potentially in a new thread
	 */
	public final ISchedulingStrategy EACH_EDGE_INDIVIDUALLY = new ISchedulingStrategy() {

		@Override
		public void propagateInitialSeeds(Abstraction sourceVal, Unit target, Abstraction targetVal,
				Unit relatedCallSite, boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn,
					ScheduleTarget.EXECUTOR);
		}

		@Override
		public void propagateNormalFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
				boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn,
					ScheduleTarget.EXECUTOR);
		}

		@Override
		public void propagateCallFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
				boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn,
					ScheduleTarget.EXECUTOR);
		}

		@Override
		public void propagateCallToReturnFlow(Abstraction sourceVal, Unit target, Abstraction targetVal,
				Unit relatedCallSite, boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn,
					ScheduleTarget.EXECUTOR);
		}

		@Override
		public void propagateReturnFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
				boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn,
					ScheduleTarget.EXECUTOR);
		};

	};

	/**
	 * Strategy that schedules each edge that crosses a method boundary, but
	 * processes all edges inside the same method locally in the same task
	 */
	public final ISchedulingStrategy EACH_METHOD_INDIVIDUALLY = new ISchedulingStrategy() {

		@Override
		public void propagateInitialSeeds(Abstraction sourceVal, Unit target, Abstraction targetVal,
				Unit relatedCallSite, boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn,
					ScheduleTarget.EXECUTOR);
		}

		@Override
		public void propagateNormalFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
				boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn, ScheduleTarget.LOCAL);
		}

		@Override
		public void propagateCallFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
				boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn,
					ScheduleTarget.EXECUTOR);
		}

		@Override
		public void propagateCallToReturnFlow(Abstraction sourceVal, Unit target, Abstraction targetVal,
				Unit relatedCallSite, boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn, ScheduleTarget.LOCAL);
		}

		@Override
		public void propagateReturnFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
				boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn,
					ScheduleTarget.EXECUTOR);
		};

	};

	/**
	 * Strategy that never schedules a new task, i.e., processes all edges locally
	 * in same task
	 */
	public final ISchedulingStrategy ALL_EDGES_LOCALLY = new ISchedulingStrategy() {

		@Override
		public void propagateInitialSeeds(Abstraction sourceVal, Unit target, Abstraction targetVal,
				Unit relatedCallSite, boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn, ScheduleTarget.LOCAL);
		}

		@Override
		public void propagateNormalFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
				boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn, ScheduleTarget.LOCAL);
		}

		@Override
		public void propagateCallFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
				boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn, ScheduleTarget.LOCAL);
		}

		@Override
		public void propagateCallToReturnFlow(Abstraction sourceVal, Unit target, Abstraction targetVal,
				Unit relatedCallSite, boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn, ScheduleTarget.LOCAL);
		}

		@Override
		public void propagateReturnFlow(Abstraction sourceVal, Unit target, Abstraction targetVal, Unit relatedCallSite,
				boolean isUnbalancedReturn) {
			solver.propagate(sourceVal, target, targetVal, relatedCallSite, isUnbalancedReturn, ScheduleTarget.LOCAL);
		};

	};

	/**
	 * Creates a new instance of the {@link DefaultSchedulingStrategy} class
	 * 
	 * @param solver The solver on which to schedule the edges
	 */
	public DefaultSchedulingStrategy(AbstractIFDSSolver solver) {
		this.solver = solver;
	}

}
