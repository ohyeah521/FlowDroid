package soot.jimple.infoflow.solver.gcSolver;

import heros.solver.PathEdge;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;
import soot.util.ConcurrentHashMultiMap;

/**
 * The default garbage collector implementation
 * 
 * @author Steven Arzt
 *
 */
public class DefaultGarbageCollector extends MethodLevelReferenceCountingGarbageCollector {

	public DefaultGarbageCollector(BiDiInterproceduralCFG<Unit, SootMethod> icfg,
			ConcurrentHashMultiMap<SootMethod, PathEdge<Unit, Abstraction>> jumpFunctions) {
		super(icfg, jumpFunctions);
	}

	public DefaultGarbageCollector(BiDiInterproceduralCFG<Unit, SootMethod> icfg,
			ConcurrentHashMultiMap<SootMethod, PathEdge<Unit, Abstraction>> jumpFunctions,
			IGCReferenceProvider<SootMethod> referenceProvider) {
		super(icfg, jumpFunctions, referenceProvider);
	}

	@Override
	public void gc() {
		gcImmediate();
	}

	@Override
	public void notifySolverTerminated() {
		// nothing to do here
	}

}
