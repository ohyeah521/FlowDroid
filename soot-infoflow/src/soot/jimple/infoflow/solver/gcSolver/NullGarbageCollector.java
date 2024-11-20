package soot.jimple.infoflow.solver.gcSolver;

import heros.solver.PathEdge;
import soot.Unit;
import soot.jimple.infoflow.data.Abstraction;

/**
 * Mock implementation for a garbage collector that does nothing
 * 
 * @author Steven Arzt
 *
 */
public class NullGarbageCollector implements IGarbageCollector {

	@Override
	public void notifyEdgeSchedule(PathEdge<Unit, Abstraction> edge) {
		// do nothing
	}

	@Override
	public void notifyTaskProcessed(PathEdge<Unit, Abstraction> edge) {
		// do nothing
	}

	@Override
	public void gc() {
		// do nothing
	}

	@Override
	public int getGcedAbstractions() {
		return 0;
	}

	@Override
	public int getGcedEdges() {
		return 0;
	}

	@Override
	public void notifySolverTerminated() {
	}

}
