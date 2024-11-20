package soot.jimple.infoflow.solver.gcSolver.fpc;

import heros.solver.Pair;
import heros.solver.PathEdge;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.solver.gcSolver.IGCReferenceProvider;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;
import soot.util.ConcurrentHashMultiMap;

public class AggressiveGarbageCollector extends FineGrainedReferenceCountingGarbageCollector {

	public AggressiveGarbageCollector(BiDiInterproceduralCFG<Unit, SootMethod> icfg,
			ConcurrentHashMultiMap<Pair<SootMethod, Abstraction>, PathEdge<Unit, Abstraction>> jumpFunctions,
			IGCReferenceProvider<Pair<SootMethod, Abstraction>> referenceProvider) {
		super(icfg, jumpFunctions, referenceProvider);
	}

	public AggressiveGarbageCollector(BiDiInterproceduralCFG<Unit, SootMethod> icfg,
			ConcurrentHashMultiMap<Pair<SootMethod, Abstraction>, PathEdge<Unit, Abstraction>> jumpFunctions) {
		super(icfg, jumpFunctions);
	}

	@Override
	protected IGCReferenceProvider<Pair<SootMethod, Abstraction>> createReferenceProvider() {
		return null;
	}

	@Override
	public boolean hasActiveDependencies(Pair<SootMethod, Abstraction> abstraction) {
		int changeCounter = -1;
		do {
			// Update the change counter for the next round
			changeCounter = jumpFnCounter.getChangeCounter();

			// Check the method itself
			if (jumpFnCounter.get(abstraction) > 0)
				return true;

		} while (checkChangeCounter && changeCounter != jumpFnCounter.getChangeCounter());
		return false;
	}

}
