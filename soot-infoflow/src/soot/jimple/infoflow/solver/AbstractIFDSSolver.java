package soot.jimple.infoflow.solver;

import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;

import heros.DontSynchronize;
import heros.FlowFunctionCache;
import heros.FlowFunctions;
import heros.IFDSTabulationProblem;
import heros.SynchronizedBy;
import heros.ZeroedFlowFunctions;
import heros.solver.PathEdge;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.memory.IMemoryBoundedSolver;
import soot.jimple.infoflow.memory.ISolverTerminationReason;
import soot.jimple.infoflow.solver.cfg.IInfoflowCFG;
import soot.jimple.infoflow.solver.executors.InterruptableExecutor;
import soot.jimple.infoflow.solver.executors.SetPoolExecutor;
import soot.jimple.infoflow.solver.fastSolver.DefaultSchedulingStrategy;
import soot.jimple.infoflow.solver.fastSolver.IFDSSolver;
import soot.jimple.infoflow.solver.fastSolver.ISchedulingStrategy;
import soot.jimple.infoflow.solver.fastSolver.LocalWorklistTask;
import soot.jimple.infoflow.solver.memory.IMemoryManager;

/**
 * Common superclass of all IFDS solvers.
 * 
 * @author Steven Arzt
 */
public abstract class AbstractIFDSSolver implements IMemoryBoundedSolver, IStrategyBasedParallelSolver {

	/**
	 * Possible targets where to schedule an edge for processing
	 */
	public enum ScheduleTarget {
		/**
		 * Try to run on the same thread within the executor
		 */
		LOCAL,

		/**
		 * Run possibly on another executor
		 */
		EXECUTOR;
	}

	protected static final Logger logger = LoggerFactory.getLogger(AbstractIFDSSolver.class);

	// enable with -Dorg.slf4j.simpleLogger.defaultLogLevel=trace
	public static final boolean DEBUG = logger.isDebugEnabled();

	public static CacheBuilder<Object, Object> DEFAULT_CACHE_BUILDER = CacheBuilder.newBuilder()
			.concurrencyLevel(Runtime.getRuntime().availableProcessors()).initialCapacity(10000).softValues();

	protected ISolverPeerGroup solverPeerGroup = null;
	protected boolean solverId;

	protected InterruptableExecutor executor;

	@DontSynchronize("stateless")
	protected final Abstraction zeroValue;

	@SynchronizedBy("thread safe data structure, only modified internally")
	protected final IInfoflowCFG icfg;

	@DontSynchronize("only used by single thread")
	protected int numThreads;

	@DontSynchronize("readOnly")
	protected PredecessorShorteningMode shorteningMode = PredecessorShorteningMode.NeverShorten;

	@DontSynchronize("readOnly")
	protected final FlowFunctionCache<Unit, Abstraction, SootMethod> ffCache;

	@DontSynchronize("stateless")
	protected final FlowFunctions<Unit, Abstraction, SootMethod> flowFunctions;

	@DontSynchronize("only used by single thread")
	protected final Map<Unit, Set<Abstraction>> initialSeeds;

	@DontSynchronize("readOnly")
	protected final boolean followReturnsPastSeeds;

	@DontSynchronize("readOnly")
	protected IMemoryManager memoryManager = null;

	@DontSynchronize("benign races")
	public long propagationCount;

	@DontSynchronize("readOnly")
	protected int maxCalleesPerCallSite = 10;
	@DontSynchronize("readOnly")
	protected int maxAbstractionPathLength = 100;
	@DontSynchronize("readOnly")
	protected int maxJoinPointAbstractions = -1;

	protected ISchedulingStrategy schedulingStrategy = new DefaultSchedulingStrategy(this).EACH_EDGE_INDIVIDUALLY;

	protected Set<IMemoryBoundedSolverStatusNotification> notificationListeners = new HashSet<>();

	protected ISolverTerminationReason killFlag = null;

	/**
	 * Creates a solver for the given problem, constructing caches with the given
	 * {@link CacheBuilder}. The solver must then be started by calling
	 * {@link #solve()}.
	 * 
	 * @param tabulationProblem        The tabulation problem to solve
	 * @param flowFunctionCacheBuilder A valid {@link CacheBuilder} or
	 *                                 <code>null</code> if no caching is to be used
	 *                                 for flow functions.
	 */
	public AbstractIFDSSolver(IFDSTabulationProblem<Unit, Abstraction, SootMethod, IInfoflowCFG> tabulationProblem,
			@SuppressWarnings("rawtypes") CacheBuilder flowFunctionCacheBuilder) {
		if (logger.isDebugEnabled())
			flowFunctionCacheBuilder = flowFunctionCacheBuilder.recordStats();

		this.zeroValue = tabulationProblem.zeroValue();

		FlowFunctions<Unit, Abstraction, SootMethod> flowFunctions = tabulationProblem.autoAddZero()
				? new ZeroedFlowFunctions<Unit, Abstraction, SootMethod>(tabulationProblem.flowFunctions(), zeroValue)
				: tabulationProblem.flowFunctions();
		if (flowFunctionCacheBuilder != null) {
			ffCache = new FlowFunctionCache<Unit, Abstraction, SootMethod>(flowFunctions, flowFunctionCacheBuilder);
			flowFunctions = ffCache;
		} else {
			ffCache = null;
		}
		this.flowFunctions = flowFunctions;

		this.icfg = tabulationProblem.interproceduralCFG();
		this.numThreads = Math.max(1, tabulationProblem.numThreads());
		this.initialSeeds = tabulationProblem.initialSeeds();
		this.followReturnsPastSeeds = tabulationProblem.followReturnsPastSeeds();
		this.executor = getExecutor();
	}

	/**
	 * Sets whether abstractions on method returns shall be connected to the
	 * respective call abstractions to shortcut paths.
	 *
	 * @param mode The strategy to use for shortening predecessor paths
	 */
	public void setPredecessorShorteningMode(PredecessorShorteningMode mode) {
		this.shorteningMode = mode;
	}

	/**
	 * Shortens the predecessors of the first argument if configured.
	 *
	 * @param returnD   data flow fact leaving the method, which predecessor chain
	 *                  should be shortened
	 * @param incomingD incoming data flow fact at the call site
	 * @param calleeD   first data flow fact in the callee, i.e. with current
	 *                  statement == call site
	 * @return data flow fact with a shortened predecessor chain
	 */
	protected Abstraction shortenPredecessors(Abstraction returnD, Abstraction incomingD, Abstraction calleeD,
			Unit currentUnit, Unit callSite) {
		switch (shorteningMode) {
		case AlwaysShorten:
			// If we don't build a path later, we do not have to keep flows through callees.
			// But we have to keep the call site so that we do not lose any neighbors, but
			// skip any abstractions inside the callee. This sets the predecessor of the
			// returned abstraction to the first abstraction in the callee.
			if (returnD != calleeD) {
				Abstraction res = returnD.clone(currentUnit, callSite);
				res.setPredecessor(calleeD);
				return res;
			}
			break;
		case ShortenIfEqual:
			if (returnD.equals(incomingD))
				return incomingD;
			break;
		}

		return returnD;
	}

	/**
	 * Factory method for this solver's thread-pool executor.
	 */
	protected InterruptableExecutor getExecutor() {
		SetPoolExecutor executor = new SetPoolExecutor(1, this.numThreads, 30, TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>());
		executor.setThreadFactory(new ThreadFactory() {

			@Override
			public Thread newThread(Runnable r) {
				Thread thrIFDS = new Thread(r);
				thrIFDS.setDaemon(true);
				thrIFDS.setName("IFDS Solver");
				return thrIFDS;
			}
		});
		return executor;
	}

	/**
	 * Sets the memory manager that shall be used to manage the abstractions
	 * 
	 * @param memoryManager The memory manager that shall be used to manage the
	 *                      abstractions
	 */
	public void setMemoryManager(IMemoryManager memoryManager) {
		this.memoryManager = memoryManager;
	}

	/**
	 * Gets the memory manager used by this solver to reduce memory consumption
	 * 
	 * @return The memory manager registered with this solver
	 */
	public IMemoryManager getMemoryManager() {
		return this.memoryManager;
	}

	/**
	 * Awaits the completion of the exploded super graph. When complete, computes
	 * result values, shuts down the executor and returns.
	 */
	protected void awaitCompletionComputeValuesAndShutdown() {
		{
			// run executor and await termination of tasks
			runExecutorAndAwaitCompletion();
		}
		if (logger.isDebugEnabled())
			printStats();

		// ask executor to shut down;
		// this will cause new submissions to the executor to be rejected,
		// but at this point all tasks should have completed anyway
		executor.shutdown();

		// Wait for the executor to be really gone
		while (!executor.isTerminated()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				// silently ignore the exception, it's not an issue if the
				// thread gets aborted
			}
		}
	}

	/**
	 * Runs execution, re-throwing exceptions that might be thrown during its
	 * execution.
	 */
	private void runExecutorAndAwaitCompletion() {
		try {
			executor.awaitCompletion();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		Throwable exception = executor.getException();
		if (exception != null) {
			throw new RuntimeException("There were exceptions during IFDS analysis. Exiting.", exception);
		}
	}

	/**
	 * Schedules the processing of initial seeds, initiating the analysis. Clients
	 * should only call this methods if performing synchronization on their own.
	 * Normally, {@link #solve()} should be called instead.
	 */
	protected void submitInitialSeeds() {
		for (Entry<Unit, Set<Abstraction>> seed : initialSeeds.entrySet()) {
			Unit startPoint = seed.getKey();
			for (Abstraction val : seed.getValue())
				schedulingStrategy.propagateInitialSeeds(zeroValue, startPoint, val, null, false);
			addFunction(new PathEdge<Unit, Abstraction>(zeroValue, startPoint, zeroValue));
		}
	}

	/**
	 * Records a jump function. The source statement is implicit.
	 *
	 * @see PathEdge
	 */
	public abstract Abstraction addFunction(PathEdge<Unit, Abstraction> edge);

	/**
	 * Runs the solver on the configured problem. This can take some time.
	 */
	public void solve() {
		reset();

		// Notify the listeners that the solver has been started
		for (IMemoryBoundedSolverStatusNotification listener : notificationListeners)
			listener.notifySolverStarted(this);

		submitInitialSeeds();
		awaitCompletionComputeValuesAndShutdown();

		// Notify the listeners that the solver has been terminated
		for (IMemoryBoundedSolverStatusNotification listener : notificationListeners)
			listener.notifySolverTerminated(this);
	}

	/**
	 * Propagates the flow further down the exploded super graph.
	 *
	 * @param sourceVal          the source value of the propagated summary edge
	 * @param target             the target statement
	 * @param targetVal          the target value at the target statement
	 * @param relatedCallSite    for call and return flows the related call
	 *                           statement, <code>null</code> otherwise (this value
	 *                           is not used within this implementation but may be
	 *                           useful for subclasses of {@link IFDSSolver})
	 * @param isUnbalancedReturn <code>true</code> if this edge is propagating an
	 *                           unbalanced return (this value is not used within
	 *                           this implementation but may be useful for
	 *                           subclasses of {@link IFDSSolver})
	 * @param scheduleTarget     The target where to schedule the new edge
	 */
	protected void propagate(Abstraction sourceVal, Unit target, Abstraction targetVal,
			/* deliberately exposed to clients */ Unit relatedCallSite,
			/* deliberately exposed to clients */ boolean isUnbalancedReturn, ScheduleTarget scheduleTarget) {
		// Let the memory manager run
		if (memoryManager != null) {
			sourceVal = memoryManager.handleMemoryObject(sourceVal);
			targetVal = memoryManager.handleMemoryObject(targetVal);
			if (targetVal == null)
				return;
		}

		// Check the path length
		if (maxAbstractionPathLength >= 0 && targetVal.getPathLength() > maxAbstractionPathLength)
			return;

		final PathEdge<Unit, Abstraction> edge = new PathEdge<>(sourceVal, target, targetVal);
		final Abstraction existingVal = addFunction(edge);
		if (existingVal != null) {
			if (existingVal != targetVal) {
				// Check whether we need to retain this abstraction
				boolean isEssential;
				if (memoryManager == null)
					isEssential = relatedCallSite != null && icfg.isCallStmt(relatedCallSite);
				else
					isEssential = memoryManager.isEssentialJoinPoint(targetVal, relatedCallSite);

				if (maxJoinPointAbstractions < 0 || existingVal.getNeighborCount() < maxJoinPointAbstractions
						|| isEssential) {
					existingVal.addNeighbor(targetVal);
				}
			}
		} else {
			scheduleEdgeProcessing(edge, scheduleTarget);
		}
	}

	public void printStats() {
		if (logger.isDebugEnabled()) {
			if (ffCache != null)
				ffCache.printStats();
		} else {
			logger.info("No statistics were collected, as DEBUG is disabled.");
		}
	}

	@Override
	public void addStatusListener(IMemoryBoundedSolverStatusNotification listener) {
		this.notificationListeners.add(listener);
	}

	protected class PathEdgeProcessingTask extends LocalWorklistTask {

		protected final PathEdge<Unit, Abstraction> edge;
		protected final boolean solverId;

		public PathEdgeProcessingTask(PathEdge<Unit, Abstraction> edge, boolean solverId) {
			this.edge = edge;
			this.solverId = solverId;
		}

		public void runInternal() {
			final Unit target = edge.getTarget();
			if (icfg.isCallStmt(target)) {
				processCall(edge);
			} else {
				// note that some statements, such as "throw" may be
				// both an exit statement and a "normal" statement
				if (icfg.isExitStmt(target))
					processExit(edge);
				if (!icfg.getSuccsOf(target).isEmpty())
					processNormalFlow(edge);
			}
			notifyEdgeProcessed(edge);
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((edge == null) ? 0 : edge.hashCode());
			result = prime * result + (solverId ? 1231 : 1237);
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			PathEdgeProcessingTask other = (PathEdgeProcessingTask) obj;
			if (edge == null) {
				if (other.edge != null)
					return false;
			} else if (!edge.equals(other.edge))
				return false;
			if (solverId != other.solverId)
				return false;
			return true;
		}

	}

	/**
	 * Dispatch the processing of a given edge. It may be executed in a different
	 * thread.
	 *
	 * @param edge           the edge to process
	 * @param scheduleTarget
	 */
	protected void scheduleEdgeProcessing(PathEdge<Unit, Abstraction> edge, ScheduleTarget scheduleTarget) {
		// If the executor has been killed, there is little point
		// in submitting new tasks
		if (killFlag != null || executor.isTerminating() || executor.isTerminated())
			return;

		notifyEdgeScheduled(edge);
		PathEdgeProcessingTask task = new PathEdgeProcessingTask(edge, solverId);
		if (scheduleTarget == ScheduleTarget.EXECUTOR)
			executor.execute(task);
		else {
			LocalWorklistTask.scheduleLocal(task);
		}
		propagationCount++;
	}

	public void setSolverId(boolean solverId) {
		this.solverId = solverId;
	}

	protected boolean getSolverId() {
		return this.solverId;
	}

	/**
	 * Lines 13-20 of the algorithm; processing a call site in the caller's context.
	 *
	 * For each possible callee, registers incoming call edges. Also propagates
	 * call-to-return flows and summarized callee flows within the caller.
	 *
	 * @param edge an edge whose target node resembles a method call
	 */
	protected abstract void processCall(PathEdge<Unit, Abstraction> edge);

	/**
	 * Lines 21-32 of the algorithm.
	 *
	 * Stores callee-side summaries. Also, at the side of the caller, propagates
	 * intra-procedural flows to return sites using those newly computed summaries.
	 *
	 * @param edge an edge whose target node resembles a method exits
	 */
	protected abstract void processExit(PathEdge<Unit, Abstraction> edge);

	/**
	 * Lines 33-37 of the algorithm. Simply propagate normal, intra-procedural
	 * flows.
	 *
	 * @param edge
	 */
	protected abstract void processNormalFlow(PathEdge<Unit, Abstraction> edge);

	public long getPropagationCount() {
		return propagationCount;
	}

	/**
	 * Notified inherited solvers that a particular edge was processed
	 * 
	 * @param edge The edge that has been processed
	 */
	protected void notifyEdgeProcessed(PathEdge<Unit, Abstraction> edge) {

	}

	/**
	 * Notified inherited solvers that a particular edge was scheduled
	 * 
	 * @param edge The edge that has been scheduled for later processing
	 */
	protected void notifyEdgeScheduled(PathEdge<Unit, Abstraction> edge) {

	}

	@Override
	public void forceTerminate(ISolverTerminationReason reason) {
		this.killFlag = reason;
		this.executor.interrupt();
		this.executor.shutdown();
	}

	@Override
	public boolean isTerminated() {
		return killFlag != null || this.executor.isFinished();
	}

	@Override
	public boolean isKilled() {
		return killFlag != null;
	}

	@Override
	public void reset() {
		this.killFlag = null;
	}

	@Override
	public ISolverTerminationReason getTerminationReason() {
		return killFlag;
	}

	@Override
	public void setSchedulingStrategy(ISchedulingStrategy strategy) {
		this.schedulingStrategy = strategy;
	}

	/**
	 * Sets the maximum number of abstractions that shall be recorded per join
	 * point. In other words, enabling this option disables the recording of
	 * neighbors beyond the given count.
	 *
	 * @param maxJoinPointAbstractions The maximum number of abstractions per join
	 *                                 point, or -1 to record an arbitrary number of
	 *                                 join point abstractions
	 */
	public void setMaxJoinPointAbstractions(int maxJoinPointAbstractions) {
		this.maxJoinPointAbstractions = maxJoinPointAbstractions;
	}

	public void setMaxCalleesPerCallSite(int maxCalleesPerCallSite) {
		this.maxCalleesPerCallSite = maxCalleesPerCallSite;
	}

	public void setMaxAbstractionPathLength(int maxAbstractionPathLength) {
		this.maxAbstractionPathLength = maxAbstractionPathLength;
	}

}
