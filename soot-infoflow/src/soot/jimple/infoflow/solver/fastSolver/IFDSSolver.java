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
package soot.jimple.infoflow.solver.fastSolver;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
import soot.jimple.infoflow.collect.MyConcurrentHashMap;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.solver.AbstractIFDSSolver;
import soot.jimple.infoflow.solver.EndSummary;
import soot.jimple.infoflow.solver.IncomingRecord;
import soot.jimple.infoflow.solver.cfg.IInfoflowCFG;
import soot.util.ConcurrentHashMultiMap;

/**
 * A solver for an {@link IFDSTabulationProblem}. This solver is not based on
 * the IDESolver implementation in Heros for performance reasons.
 *
 * @see IFDSTabulationProblem
 */
public class IFDSSolver extends AbstractIFDSSolver {

	protected static final Logger logger = LoggerFactory.getLogger(IFDSSolver.class);

	@SynchronizedBy("thread safe data structure, consistent locking when used")
	protected MyConcurrentHashMap<PathEdge<Unit, Abstraction>, Abstraction> jumpFunctions = new MyConcurrentHashMap<>();

	// stores summaries that were queried before they were computed
	// see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on 'incoming'")
	protected final MyConcurrentHashMap<Pair<SootMethod, Abstraction>, Map<EndSummary, EndSummary>> endSummary = new MyConcurrentHashMap<>();

	// edges going along calls
	// see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on field")
	protected final ConcurrentHashMultiMap<Pair<SootMethod, Abstraction>, IncomingRecord> incoming = new ConcurrentHashMultiMap<>();

	/**
	 * Creates a solver for the given problem, which caches flow functions and edge
	 * functions. The solver must then be started by calling {@link #solve()}.
	 */
	public IFDSSolver(IFDSTabulationProblem<Unit, Abstraction, SootMethod, IInfoflowCFG> tabulationProblem) {
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
	public IFDSSolver(IFDSTabulationProblem<Unit, Abstraction, SootMethod, IInfoflowCFG> tabulationProblem,
			@SuppressWarnings("rawtypes") CacheBuilder flowFunctionCacheBuilder) {
		super(tabulationProblem, flowFunctionCacheBuilder);
	}

	@Override
	protected void processCall(PathEdge<Unit, Abstraction> edge) {
		final Abstraction d1 = edge.factAtSource();
		final Unit n = edge.getTarget(); // a call node; line 14...

		final Abstraction d2 = edge.factAtTarget();
		assert d2 != null;
		Collection<Unit> returnSiteNs = icfg.getReturnSitesOfCallAt(n);

		// for each possible callee
		Collection<SootMethod> callees = icfg.getCalleesOfCallAt(n);
		if (callees != null && !callees.isEmpty()) {
			if (maxCalleesPerCallSite < 0 || callees.size() <= maxCalleesPerCallSite) {
				callees.forEach(new Consumer<SootMethod>() {

					@Override
					public void accept(SootMethod sCalledProcN) {
						// Concrete and early termination check
						if (!sCalledProcN.isConcrete() || killFlag != null)
							return;

						// If our callee does not read the activation unit, we keep it symbolic
//						ConcolicUnit actUnit = d2.getConcolicActivationUnit();
//
//						D d2new = d2;
//						if (actUnit.isConcrete() && !sCalledProcN.getActiveBody().getUnits().contains(actUnit)) {
//							peerGroup.registerSymbolicActivationUnit(d1, d2, actUnit);
//							d2new = d2.makeActivationUnitSymbolic();
//						}

						// compute the call-flow function
						FlowFunction<Abstraction> function = flowFunctions.getCallFlowFunction(n, sCalledProcN);
						Set<Abstraction> res = computeCallFlowFunction(function, d1, d2);

						if (res != null && !res.isEmpty()) {
							Collection<Unit> startPointsOf = icfg.getStartPointsOf(sCalledProcN);
							// for each result node of the call-flow function
							for (Abstraction d3 : res) {
								if (memoryManager != null)
									d3 = memoryManager.handleGeneratedMemoryObject(d2, d3);
								if (d3 == null)
									continue;

								// for each callee's start point(s)
								for (Unit sP : startPointsOf) {
									// create initial self-loop
									schedulingStrategy.propagateCallFlow(d3, sP, d3, n, false); // line 15
								}

								// register the fact that <sp,d3> has an incoming edge from
								// <n,d2>
								// line 15.1 of Naeem/Lhotak/Rodriguez
								if (!addIncoming(sCalledProcN, d3, n, d1, d2))
									continue;

								applyEndSummaryOnCall(d1, n, d2, returnSiteNs, sCalledProcN, d3);
							}
						}
					}

				});
			}
		}

		// line 17-19 of Naeem/Lhotak/Rodriguez
		// process intra-procedural flows along call-to-return flow functions
		for (Unit returnSiteN : returnSiteNs) {
			FlowFunction<Abstraction> callToReturnFlowFunction = flowFunctions.getCallToReturnFlowFunction(n,
					returnSiteN);
			Set<Abstraction> res = computeCallToReturnFlowFunction(callToReturnFlowFunction, d1, d2);
			if (res != null && !res.isEmpty()) {
				for (Abstraction d3 : res) {
					if (memoryManager != null)
						d3 = memoryManager.handleGeneratedMemoryObject(d2, d3);
					if (d3 != null)
						schedulingStrategy.propagateCallToReturnFlow(d1, returnSiteN, d3, n, false);
				}
			}
		}
	}

	/**
	 * Callback to notify derived classes that an end summary has been applied
	 *
	 * @param n           The call site where the end summary has been applied
	 * @param sCalledProc The callee
	 * @param d3          The callee-side incoming taint abstraction
	 */
	protected void onEndSummaryApplied(Unit n, SootMethod sCalledProc, Abstraction d3) {
	}

	protected void applyEndSummaryOnCall(final Abstraction d1, final Unit n, final Abstraction d2,
			Collection<Unit> returnSiteNs, SootMethod sCalledProcN, Abstraction d3) {
		// line 15.2
		Set<EndSummary> endSumm = endSummary(sCalledProcN, d3);

		// still line 15.2 of Naeem/Lhotak/Rodriguez
		// for each already-queried exit value <eP,d4> reachable
		// from <sP,d3>, create new caller-side jump functions to
		// the return sites because we have observed a potentially
		// new incoming edge into <sP,d3>
		if (endSumm != null && !endSumm.isEmpty()) {
			for (EndSummary entry : endSumm) {
				Unit eP = entry.eP;
				Abstraction d4 = entry.d4;

				// We must acknowledge the incoming abstraction from the other path
				entry.calleeD1.addNeighbor(d3);

				// for each return site
				for (Unit retSiteN : returnSiteNs) {
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

							// If we have not changed anything in
							// the callee, we do not need the facts from
							// there. Even if we change something:
							// If we don't need the concrete path,
							// we can skip the callee in the predecessor
							// chain
							Abstraction d5p = shortenPredecessors(d5, d2, d3, eP, n);
							schedulingStrategy.propagateReturnFlow(d1, retSiteN, d5p, n, false);
						}
					}
				}
			}
			onEndSummaryApplied(n, sCalledProcN, d3);
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
		Set<IncomingRecord> inc = incoming(d1, methodThatNeedsSummary);

		// for each incoming call edge already processed
		// (see processCall(..))
		for (IncomingRecord entry : inc) {
			// Early termination check
			if (killFlag != null)
				return;

			// line 22
			Unit c = entry.n;
			Set<Abstraction> callerSideDs = Collections.singleton(entry.d1);
			// for each return site
			for (Unit retSiteC : icfg.getReturnSitesOfCallAt(c)) {
				// compute return-flow function
				FlowFunction<Abstraction> retFunction = flowFunctions.getReturnFlowFunction(c, methodThatNeedsSummary,
						n, retSiteC);
				Set<Abstraction> targets = computeReturnFlowFunction(retFunction, d1, d2, c, callerSideDs);
				// for each incoming-call value
				if (targets != null && !targets.isEmpty()) {
					final Abstraction d4 = entry.d1;
					final Abstraction predVal = entry.d2;

					for (Abstraction d5 : targets) {
						if (memoryManager != null)
							d5 = memoryManager.handleGeneratedMemoryObject(d2, d5);
						if (d5 == null)
							continue;

						// If we have not changed anything in the callee, we do not need the facts from
						// there. Even if we change something: If we don't need the concrete path, we
						// can skip the callee in the predecessor chain
						Abstraction d5p = shortenPredecessors(d5, predVal, d1, n, c);
						schedulingStrategy.propagateReturnFlow(d4, retSiteC, d5p, c, false);
					}
				}
			}

			// Make sure all of the incoming edges are registered with the edge from the new
			// summary
			d1.addNeighbor(entry.d3);
		}

		// handling for unbalanced problems where we return out of a method with
		// a fact for which we have no incoming flow
		// note: we propagate that way only values that originate from ZERO, as
		// conditionally generated values should only be propagated into callers that
		// have an incoming edge for this condition
		if (followReturnsPastSeeds && d1 == zeroValue && (inc == null || inc.isEmpty())) {
			Collection<Unit> callers = icfg.getCallersOf(methodThatNeedsSummary);
			for (Unit c : callers) {
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
								schedulingStrategy.propagateReturnFlow(zeroValue, retSiteC, d5, c, true);
						}
					}
				}
			}
			// in cases where there are no callers, the return statement would
			// normally not be processed at all; this might be undesirable if the flow
			// function has a side effect such as registering a taint; instead we thus call
			// the return flow function will a null caller
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
			// Early termination check
			if (killFlag != null)
				return;

			// Compute the flow function
			FlowFunction<Abstraction> flowFunction = flowFunctions.getNormalFlowFunction(n, m);
			Set<Abstraction> res = computeNormalFlowFunction(flowFunction, d1, d2);
			if (res != null && !res.isEmpty()) {
				for (Abstraction d3 : res) {
					if (memoryManager != null && d2 != d3)
						d3 = memoryManager.handleGeneratedMemoryObject(d2, d3);
					if (d3 != null)
						schedulingStrategy.propagateNormalFlow(d1, m, d3, null, false);
				}
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

	@Override
	public Abstraction addFunction(PathEdge<Unit, Abstraction> edge) {
		return jumpFunctions.putIfAbsent(edge, edge.factAtTarget());
	}

	protected Set<EndSummary> endSummary(SootMethod m, Abstraction d3) {
		Map<EndSummary, EndSummary> map = endSummary.get(new Pair<>(m, d3));
		return map == null ? null : map.keySet();
	}

	protected boolean addEndSummary(SootMethod m, Abstraction d1, Unit eP, Abstraction d2) {
		if (d1 == zeroValue)
			return true;

		Map<EndSummary, EndSummary> summaries = endSummary.putIfAbsentElseGet(new Pair<>(m, d1),
				() -> new ConcurrentHashMap<>());
		EndSummary newSummary = new EndSummary(eP, d2, d1);
		EndSummary existingSummary = summaries.putIfAbsent(newSummary, newSummary);
		if (existingSummary != null) {
			existingSummary.calleeD1.addNeighbor(d2);
			return false;
		}
		return true;
	}

	protected Set<IncomingRecord> incoming(Abstraction d1, SootMethod m) {
		Set<IncomingRecord> inc = incoming.get(new Pair<SootMethod, Abstraction>(m, d1));
		return inc;
	}

	protected boolean addIncoming(SootMethod m, Abstraction d3, Unit n, Abstraction d1, Abstraction d2) {
		IncomingRecord newRecord = new IncomingRecord(n, d1, d2, d3);
		IncomingRecord rec = incoming.putIfAbsent(new Pair<SootMethod, Abstraction>(m, d3), newRecord);
		return rec == null;
	}

	/**
	 * Returns a String used to identify the output of this solver in debug mode.
	 * Subclasses can overwrite this string to distinguish the output from different
	 * solvers.
	 */
	protected String getDebugName() {
		return "FAST IFDS SOLVER";
	}

}
