package soot.jimple.infoflow.solver.gcSolver;

import heros.solver.PathEdge;
import soot.Unit;
import soot.jimple.infoflow.data.Abstraction;

/**
 * Common interface for all garbage collector implementations oin the solver
 * 
 * @author Steven Arzt
 *
 */
public interface IGarbageCollector {

	/**
	 * Notifies the garbage collector that a new edge has been scheduled for
	 * processing
	 * 
	 * @param edge The edge that has been scheduled
	 */
	public void notifyEdgeSchedule(PathEdge<Unit, Abstraction> edge);

	/**
	 * Notifies the garbage collector that an edge has been fully processed
	 * 
	 * @param edge The edge has been fully processed
	 */
	public void notifyTaskProcessed(PathEdge<Unit, Abstraction> edge);

	/**
	 * Performs the garbage collection
	 */
	public void gc();

	/**
	 * Gets the number of methods for which taint abstractions were removed during
	 * garbage collection
	 * 
	 * @return The number of methods for which taint abstractions were removed
	 *         during garbage collection
	 */
	public int getGcedAbstractions();

	/**
	 * Gets the number of taint abstractions that were removed during garbage
	 * collection
	 * 
	 * @return The number of taint abstractions that were removed during garbage
	 *         collection
	 */
	public int getGcedEdges();

	/**
	 * Notifies the garbage collector that the IFDS solver has finished propagating
	 * its edges
	 */
	public void notifySolverTerminated();

}
