/*******************************************************************************
 * Copyright (c) 2012 Eric Bodden.
 * Copyright (c) 2013 Tata Consultancy Services & Ecole Polytechnique de Montreal
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors:
 *     Eric Bodden - initial API and implementation
 *     Marc-Andre Laverdiere-Papineau - Fixed race condition
 *     Steven Arzt - Created FastSolver implementation
 ******************************************************************************/
package soot.jimple.infoflow.solver.fastSolver.flowInsensitive;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.checkerframework.checker.units.qual.N;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;

import heros.FlowFunction;
import heros.IFDSTabulationProblem;
import heros.SynchronizedBy;
import heros.solver.Pair;
import heros.solver.PathEdge;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.infoflow.collect.ConcurrentHashSet;
import soot.jimple.infoflow.collect.MyConcurrentHashMap;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.solver.AbstractIFDSSolver;
import soot.jimple.infoflow.solver.EndSummary;
import soot.jimple.infoflow.solver.cfg.IInfoflowCFG;
import soot.jimple.infoflow.solver.executors.InterruptableExecutor;
import soot.jimple.infoflow.solver.executors.SetPoolExecutor;

/**
 * A solver for an {@link IFDSTabulationProblem}. This solver is not based on
 * the IDESolver implementation in Heros for performance reasons.
 * 
 * @see IFDSTabulationProblem
 */
public class FlowInsensitiveSolver extends AbstractIFDSSolver {

	protected static final Logger logger = LoggerFactory.getLogger(FlowInsensitiveSolver.class);

	@SynchronizedBy("thread safe data structure, consistent locking when used")
	protected MyConcurrentHashMap<PathEdge<SootMethod, Abstraction>, Abstraction> jumpFunctions = new MyConcurrentHashMap<>();

	// stores summaries that were queried before they were computed
	// see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on 'incoming'")
	protected final MyConcurrentHashMap<Pair<SootMethod, Abstraction>, Set<EndSummary>> endSummary = new MyConcurrentHashMap<>();

	// edges going along calls
	// see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on field")
	protected final MyConcurrentHashMap<Pair<SootMethod, Abstraction>, MyConcurrentHashMap<Unit, Map<Abstraction, Abstraction>>> incoming = new MyConcurrentHashMap<>();

	/**
	 * Creates a solver for the given problem, which caches flow functions and edge
	 * functions. The solver must then be started by calling {@link #solve()}.
	 */
	public FlowInsensitiveSolver(IFDSTabulationProblem<Unit, Abstraction, SootMethod, IInfoflowCFG> tabulationProblem) {
		this(tabulationProblem, DEFAULT_CACHE_BUILDER);
	}

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
	public FlowInsensitiveSolver(IFDSTabulationProblem<Unit, Abstraction, SootMethod, IInfoflowCFG> tabulationProblem,
			@SuppressWarnings("rawtypes") CacheBuilder flowFunctionCacheBuilder) {
		super(tabulationProblem, flowFunctionCacheBuilder);
	}

	@Override
	private void processCall(Abstraction d1, Unit n, Abstraction d2) {
		Collection<Unit> returnSiteNs = icfg.getReturnSitesOfCallAt(n);

		// for each possible callee
		Collection<SootMethod> callees = icfg.getCalleesOfCallAt(n);
		if (maxCalleesPerCallSite < 0 || callees.size() <= maxCalleesPerCallSite) {
			for (SootMethod sCalledProcN : callees) { // still line 14
				// Early termination check
				if (killFlag != null)
					return;
				if (!sCalledProcN.isConcrete())
					continue;

				// compute the call-flow function
				FlowFunction<Abstraction> function = flowFunctions.getCallFlowFunction(n, sCalledProcN);
				Set<Abstraction> res = computeCallFlowFunction(function, d1, d2);

				// for each result node of the call-flow function
				if (res != null && !res.isEmpty()) {
					for (Abstraction d3 : res) {
						if (memoryManager != null)
							d3 = memoryManager.handleGeneratedMemoryObject(d2, d3);
						if (d3 == null)
							continue;

						// for each callee's start point(s), create initial
						// self-loop
						propagate(d3, sCalledProcN, d3, n, false); // line 15

						// register the fact that <sp,d3> has an incoming edge from
						// <n,d2>
						// line 15.1 of Naeem/Lhotak/Rodriguez
						if (!addIncoming(sCalledProcN, d3, n, d1, d2))
							continue;

						applyEndSummaryOnCall(d1, n, d2, returnSiteNs, sCalledProcN, d3);
					}
				}
			}
		}

		// line 17-19 of Naeem/Lhotak/Rodriguez
		// process intra-procedural flows along call-to-return flow functions
		for (Unit returnSiteN : returnSiteNs) {
			SootMethod retMeth = icfg.getMethodOf(returnSiteN);
			FlowFunction<Abstraction> callToReturnFlowFunction = flowFunctions.getCallToReturnFlowFunction(n,
					returnSiteN);
			Set<Abstraction> res = computeCallToReturnFlowFunction(callToReturnFlowFunction, d1, d2);
			if (res != null && !res.isEmpty()) {
				for (Abstraction d3 : res) {
					if (memoryManager != null)
						d3 = memoryManager.handleGeneratedMemoryObject(d2, d3);
					if (d3 != null)
						propagate(d1, retMeth, d3, n, false);
				}
			}
		}
	}

	protected void applyEndSummaryOnCall(Abstraction d1, Unit n, Abstraction d2, Collection<Unit> returnSiteNs,
			SootMethod sCalledProcN, Abstraction d3) {
		// line 15.2
		Set<EndSummary> endSumm = endSummary(sCalledProcN, d3);

		// still line 15.2 of Naeem/Lhotak/Rodriguez
		// for each already-queried exit value <eP,d4> reachable
		// from <sP,d3>, create new caller-side jump functions to the return
		// sites because we have observed a potentially new incoming edge into
		// <sP,d3>
		if (endSumm != null && !endSumm.isEmpty()) {
			for (EndSummary entry : endSumm) {
				Unit eP = entry.eP;
				Abstraction d4 = entry.d4;
				// for each return site
				for (Unit retSiteN : returnSiteNs) {
					SootMethod retMeth = icfg.getMethodOf(retSiteN);
					// compute return-flow function
					FlowFunction<Abstraction> retFunction = flowFunctions.getReturnFlowFunction(n, sCalledProcN, eP,
							retSiteN);
					Set<Abstraction> retFlowRes = computeReturnFlowFunction(retFunction, d3, d4, n,
							Collections.singleton(d1));
					if (retFlowRes != null && !retFlowRes.isEmpty()) {
						// for each target value of the function
						for (Abstraction d5 : retFlowRes) {
							if (memoryManager != null)
								d5 = memoryManager.handleGeneratedMemoryObject(d4, d5);

							// If we have not changed anything in the callee, we
							// do not need the facts from there. Even if we
							// change something: If we don't need the concrete
							// path, we can skip the callee in the predecessor
							// chain
							Abstraction d5p = shortenPredecessors(d5, d2, d3, eP, n);
							propagate(d1, retMeth, d5p, n, false);
						}
					}
				}
			}
		}
	}

	/**
	 * Computes the call flow function for the given call-site abstraction
	 * 
	 * @param callFlowFunction The call flow function to compute
	 * @param d1               The abstraction at the current method's start node.
	 * @param d2               The abstraction at the call site
	 * @return The set of caller-side abstractions at the callee's start node
	 */
	protected Set<Abstraction> computeCallFlowFunction(FlowFunction<Abstraction> callFlowFunction, Abstraction d1,
			Abstraction d2) {
		return callFlowFunction.computeTargets(d2);
	}

	/**
	 * Computes the call-to-return flow function for the given call-site abstraction
	 * 
	 * @param callToReturnFlowFunction The call-to-return flow function to compute
	 * @param d1                       The abstraction at the current method's start
	 *                                 node.
	 * @param d2                       The abstraction at the call site
	 * @return The set of caller-side abstractions at the return site
	 */
	protected Set<Abstraction> computeCallToReturnFlowFunction(FlowFunction<Abstraction> callToReturnFlowFunction,
			Abstraction d1, Abstraction d2) {
		return callToReturnFlowFunction.computeTargets(d2);
	}

	@Override
	protected void processExit(PathEdge<Unit, Abstraction> edge) {
		final Unit n = edge.getTarget(); // an exit node; line 21...
		SootMethod methodThatNeedsSummary = icfg.getMethodOf(n);

		final Abstraction d1 = edge.factAtSource();
		final Abstraction d2 = edge.factAtTarget();

		// for each of the method's start points, determine incoming calls

		// line 21.1 of Naeem/Lhotak/Rodriguez
		// register end-summary
		if (!addEndSummary(methodThatNeedsSummary, d1, n, d2))
			return;
		Map<Unit, Map<Abstraction, Abstraction>> inc = incoming(d1, methodThatNeedsSummary);

		// for each incoming call edge already processed
		// (see processCall(..))
		if (inc != null && !inc.isEmpty()) {
			for (Entry<Unit, Map<Abstraction, Abstraction>> entry : inc.entrySet()) {
				// line 22
				Unit c = entry.getKey();
				Set<Abstraction> callerSideDs = entry.getValue().keySet();
				// for each return site
				for (Unit retSiteC : icfg.getReturnSitesOfCallAt(c)) {
					SootMethod returnMeth = icfg.getMethodOf(retSiteC);
					// compute return-flow function
					FlowFunction<Abstraction> retFunction = flowFunctions.getReturnFlowFunction(c,
							methodThatNeedsSummary, n, retSiteC);
					Set<Abstraction> targets = computeReturnFlowFunction(retFunction, d1, d2, c, callerSideDs);
					// for each incoming-call value
					for (Entry<Abstraction, Abstraction> d1d2entry : entry.getValue().entrySet()) {
						final Abstraction d4 = d1d2entry.getKey();
						final Abstraction predVal = d1d2entry.getValue();

						for (Abstraction d5 : targets) {
							if (memoryManager != null)
								d5 = memoryManager.handleGeneratedMemoryObject(d2, d5);
							if (d5 == null)
								continue;

							// If we have not changed anything in the callee, we
							// do not need the facts
							// from there. Even if we change something: If we
							// don't need the concrete
							// path, we can skip the callee in the predecessor
							// chain
							Abstraction d5p = shortenPredecessors(d5, predVal, d1, n, c);
							propagate(d4, returnMeth, d5p, c, false);
						}
					}
				}
			}
		}

		// handling for unbalanced problems where we return out of a method with
		// a fact
		// for which we have no incoming flow
		// note: we propagate that way only values that originate from ZERO, as
		// conditionally generated values should only
		// be propagated into callers that have an incoming edge for this
		// condition
		if (followReturnsPastSeeds && d1 == zeroValue && (inc == null || inc.isEmpty())) {
			Collection<Unit> callers = icfg.getCallersOf(methodThatNeedsSummary);
			for (Unit c : callers) {
				SootMethod callerMethod = icfg.getMethodOf(c);
				for (Unit retSiteC : icfg.getReturnSitesOfCallAt(c)) {
					FlowFunction<Abstraction> retFunction = flowFunctions.getReturnFlowFunction(c,
							methodThatNeedsSummary, n, retSiteC);
					Set<Abstraction> targets = computeReturnFlowFunction(retFunction, d1, d2, c,
							Collections.singleton(zeroValue));
					if (targets != null && !targets.isEmpty()) {
						for (Abstraction d5 : targets) {
							if (memoryManager != null)
								d5 = memoryManager.handleGeneratedMemoryObject(d2, d5);
							if (d5 != null)
								propagate(zeroValue, callerMethod, d5, c, true);
						}
					}
				}
			}
			// in cases where there are no callers, the return statement would
			// normally not
			// be processed at all;
			// this might be undesirable if the flow function has a side effect
			// such as
			// registering a taint;
			// instead we thus call the return flow function will a null caller
			if (callers.isEmpty()) {
				FlowFunction<Abstraction> retFunction = flowFunctions.getReturnFlowFunction(null,
						methodThatNeedsSummary, n, null);
				retFunction.computeTargets(d2);
			}
		}
	}

	/**
	 * Computes the return flow function for the given set of caller-side
	 * abstractions.
	 * 
	 * @param retFunction  The return flow function to compute
	 * @param d1           The abstraction at the beginning of the callee
	 * @param d2           The abstraction at the exit node in the callee
	 * @param callSite     The call site
	 * @param callerSideDs The abstractions at the call site
	 * @return The set of caller-side abstractions at the return site
	 */
	protected Set<Abstraction> computeReturnFlowFunction(FlowFunction<Abstraction> retFunction, Abstraction d1,
			Abstraction d2, Unit callSite, Collection<Abstraction> callerSideDs) {
		return retFunction.computeTargets(d2);
	}

	@Override
	protected void processNormalFlow(PathEdge<Unit, Abstraction> edge) {
		final Abstraction d1 = edge.factAtSource();
		final Unit n = edge.getTarget();
		final Abstraction d2 = edge.factAtTarget();

		for (Unit m : icfg.getSuccsOf(n)) {
			FlowFunction<Abstraction> flowFunction = flowFunctions.getNormalFlowFunction(n, m);
			Set<Abstraction> res = computeNormalFlowFunction(flowFunction, d1, d2);
			if (res != null && !res.isEmpty()) {
				for (Abstraction d3 : res) {
					if (memoryManager != null && d2 != d3)
						d3 = memoryManager.handleGeneratedMemoryObject(d2, d3);
					if (d3 != null && d3 != d2)
						propagate(d1, method, d3, null, false);
				}
			}
		}
	}

	private void processMethod(PathEdge<SootMethod, Abstraction> edge) {
		Abstraction d1 = edge.factAtSource();
		SootMethod target = edge.getTarget();
		Abstraction d2 = edge.factAtTarget();

		// Iterate over all statements in the method and apply the propagation
		for (Unit u : target.getActiveBody().getUnits()) {
			if (icfg.isCallStmt(u))
				processCall(d1, u, d2);
			else {
				if (icfg.isExitStmt(u))
					processExit(d1, u, d2);
				if (!icfg.getSuccsOf(u).isEmpty())
					processNormalFlow(d1, u, d2, target);
			}
		}
	}

	/**
	 * Computes the normal flow function for the given set of start and end
	 * abstractions.
	 * 
	 * @param flowFunction The normal flow function to compute
	 * @param d1           The abstraction at the method's start node
	 * @param d2           The abstraction at the current node
	 * @return The set of abstractions at the successor node
	 */
	protected Set<Abstraction> computeNormalFlowFunction(FlowFunction<Abstraction> flowFunction, Abstraction d1,
			Abstraction d2) {
		return flowFunction.computeTargets(d2);
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
	 *                           useful for subclasses of
	 *                           {@link FlowInsensitiveSolver})
	 * @param isUnbalancedReturn <code>true</code> if this edge is propagating an
	 *                           unbalanced return (this value is not used within
	 *                           this implementation but may be useful for
	 *                           subclasses of {@link FlowInsensitiveSolver})
	 * @param forceRegister      True if the jump function must always be registered
	 *                           with jumpFn . This can happen when externally
	 *                           injecting edges that don't come out of this solver.
	 */
	@SuppressWarnings("unchecked")
	protected void propagate(Abstraction sourceVal, SootMethod target, Abstraction targetVal,
			/* deliberately exposed to clients */ Unit relatedCallSite,
			/* deliberately exposed to clients */ boolean isUnbalancedReturn) {
		// Let the memory manager run
		if (memoryManager != null) {
			sourceVal = memoryManager.handleMemoryObject(sourceVal);
			targetVal = memoryManager.handleMemoryObject(targetVal);
			if (sourceVal == null || targetVal == null)
				return;
		}

		// Check the path length
		if (maxAbstractionPathLength >= 0 && targetVal.getPathLength() > maxAbstractionPathLength)
			return;

		final PathEdge<SootMethod, Abstraction> edge = new PathEdge<>(sourceVal, target, targetVal);
		final Abstraction existingVal = addFunction(edge);
		if (existingVal != null) {
			// Check whether we need to retain this abstraction
			boolean isEssential;
			if (memoryManager == null)
				isEssential = relatedCallSite != null && icfg.isCallStmt(relatedCallSite);
			else
				isEssential = memoryManager.isEssentialJoinPoint(targetVal, (N) relatedCallSite);

			if (maxJoinPointAbstractions < 0 || existingVal.getNeighborCount() < maxJoinPointAbstractions
					|| isEssential)
				existingVal.addNeighbor(targetVal);
		} else {
			scheduleEdgeProcessing(edge);
		}
	}

	@Override
	public Abstraction addFunction(PathEdge<SootMethod, Abstraction> edge) {
		return jumpFunctions.putIfAbsent(edge, edge.factAtTarget());
	}

	protected Set<EndSummary> endSummary(SootMethod m, Abstraction d3) {
		return endSummary.get(new Pair<SootMethod, Abstraction>(m, d3));
	}

	private boolean addEndSummary(SootMethod m, Abstraction d1, Unit eP, Abstraction d2) {
		if (d1 == zeroValue)
			return true;

		Set<EndSummary> summaries = endSummary.computeIfAbsent(new Pair<SootMethod, Abstraction>(m, d1),
				x -> new ConcurrentHashSet<EndSummary>());
		return summaries.add(new EndSummary(eP, d2, d1));
	}

	protected Map<Unit, Map<Abstraction, Abstraction>> incoming(Abstraction d1, SootMethod m) {
		return incoming.get(new Pair<SootMethod, Abstraction>(m, d1));
	}

	protected boolean addIncoming(SootMethod m, Abstraction d3, Unit n, Abstraction d1, Abstraction d2) {
		MyConcurrentHashMap<Unit, Map<Abstraction, Abstraction>> summaries = incoming
				.putIfAbsentElseGet(new Pair<>(m, d3), new MyConcurrentHashMap<>());
		Map<Abstraction, Abstraction> set = summaries.putIfAbsentElseGet(n, new ConcurrentHashMap<>());
		return set.put(d1, d2) == null;
	}

	@Override
	protected InterruptableExecutor getExecutor() {
		return new SetPoolExecutor(1, this.numThreads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
	}

	/**
	 * Returns a String used to identify the output of this solver in debug mode.
	 * Subclasses can overwrite this string to distinguish the output from different
	 * solvers.
	 */
	protected String getDebugName() {
		return "FLOW-INSENSIIVE IFDS SOLVER";
	}

}
