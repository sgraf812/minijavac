package minijava.ir.optimize;

import static org.jooq.lambda.Seq.seq;

import firm.*;
import firm.nodes.*;
import java.util.*;
import minijava.ir.utils.FirmUtils;
import minijava.ir.utils.GraphUtils;
import org.jooq.lambda.Seq;
import org.jooq.lambda.tuple.Tuple2;

public class Inliner extends BaseOptimizer {
  private final ProgramMetrics metrics;
  private final Set<Call> callsToInline = new HashSet<>();

  public Inliner(ProgramMetrics metrics) {
    this.metrics = metrics;
  }

  @Override
  public boolean optimize(Graph graph) {
    this.graph = graph;
    this.callsToInline.clear();
    fixedPointIteration();
    return inlineCandidates();
  }

  private boolean inlineCandidates() {
    Dump.dumpGraph(graph, "before-inlining");
    boolean hasChanged = false;
    for (Call call : callsToInline) {
      inline(call);
      hasChanged = true;
    }
    Dump.dumpGraph(graph, "after-inlining");
    return hasChanged;
  }

  private void inline(Call call) {
    Graph callee = callee(call);
    Tuple2<Start, End> subgraph = GraphUtils.copyGraph(callee, call.getGraph());
    Start start = subgraph.v1;
    End end = subgraph.v2;

    Block afterCallBlock = moveDependenciesIntoNewBlock(call);

    // We have to replace Projs to the Start node.
    // We can't yet use BackEdges since the subgraph is not yet connected to the graphs End node.
    Block startBlock = (Block) start.getBlock();
    GraphUtils.walkFromNodeDepthFirst(
        end,
        n -> {},
        node -> {
          if (startBlock.equals(node.getBlock())) {
            node.setBlock(call.getBlock());
          }

          if (!(node instanceof Proj)) {
            return;
          }

          Proj proj = (Proj) node;
          boolean isProjMOnStart =
              proj.getMode().equals(Mode.getM()) && proj.getPred().equals(start);
          if (isProjMOnStart) {
            Graph.exchange(proj, call.getPred(0));
            return;
          }

          boolean predIsTuple = proj.getPred().getMode().equals(Mode.getT());
          boolean predIsProj = proj.getPred() instanceof Proj;
          boolean predIsPredOfStart = predIsProj && proj.getPred().getPred(0).equals(start);
          boolean predIsArgsNode = predIsTuple && predIsProj && predIsPredOfStart;
          if (predIsArgsNode) {
            int argIndex = proj.getNum();
            // First pred to call is M, second is the functions Address
            Graph.exchange(proj, call.getPred(argIndex + 2));
          }
        });

    Dump.dumpGraph(graph, "after-start");

    // The End node has to replaced by Phis, one for the ret val, the other for M.
    // Other than that, we have to do pretty much the same Proj substitution at the call site
    // as we did for the callee copy for the Start node.
    Block endBlock = (Block) end.getBlock();
    int returnNodes = endBlock.getPredCount();
    MethodType mt = (MethodType) call.getType();
    int returnVals = mt.getNRess() + 1; // index 0 is M
    Node[][] phiPreds = new Node[returnVals][returnNodes];
    for (int i = 0; i < returnNodes; ++i) {
      Return ret = (Return) endBlock.getPred(i);
      for (int r = 0; r < returnVals; ++r) {
        phiPreds[r][i] = ret.getPred(r);
      }
      Graph.exchange(ret, graph.newJmp(ret.getBlock()));
    }
    Phi[] phis =
        Seq.of(phiPreds)
            .map(preds -> graph.newPhi(endBlock, preds, preds[0].getMode()))
            .toArray(Phi[]::new);

    FirmUtils.withBackEdges(
        graph,
        () -> {
          for (Proj proj : seq(BackEdges.getOuts(call)).map(be -> be.node).ofType(Proj.class)) {
            if (proj.getMode().equals(Mode.getM())) {
              Graph.exchange(proj, phis[0]);
            } else {
              assert proj.getMode().equals(Mode.getT());
              for (Proj argProj :
                  seq(BackEdges.getOuts(proj)).map(be -> be.node).ofType(Proj.class)) {
                int argIndex = argProj.getNum();
                // First pred to a Return is M, the rest are the arguments
                Graph.exchange(argProj, phis[argIndex + 1]);
              }
            }
          }
        });

    afterCallBlock.setPred(0, graph.newJmp(endBlock));
  }

  private Block moveDependenciesIntoNewBlock(Node node) {
    Jmp jmp = (Jmp) node.getGraph().newJmp(node.getBlock());
    Block newBlock = (Block) node.getGraph().newBlock(new Node[] {jmp});
    Set<Node> toMove = new HashSet<>();

    FirmUtils.withBackEdges(
        node.getGraph(),
        () -> {
          Set<Node> toVisit = new HashSet<>();
          toVisit.add(node);
          while (!toVisit.isEmpty()) {
            Node move = seq(toVisit).findAny().get();
            toVisit.remove(move);
            if (toMove.contains(move)) {
              continue;
            }
            toMove.add(move);
            toVisit.addAll(
                seq(BackEdges.getOuts(move))
                    .map(be -> be.node)
                    .filter(n -> !toMove.contains(n))
                    .filter(n -> node.getBlock().equals(n.getBlock()))
                    .toList());
          }
          toMove.remove(node); // The node itself should remain in the old block
        });

    for (Node move : toMove) {
      move.setBlock(newBlock);
    }

    return newBlock;
  }

  @Override
  public void visit(Call call) {
    Graph callee = callee(call);
    if (callee == null) {
      // This was a foreign/native call, where we don't have access to the graph.
      return;
    }
    ProgramMetrics.GraphInfo calleeInfo = metrics.graphInfos.get(callee(call));
    if (!calleeInfo.calls.isEmpty()) {
      // We only inline if the callee itself doesn't call any other functions for now
      return;
    }
    callsToInline.add(call);
  }

  private Graph callee(Call call) {
    Address funcPtr = (Address) call.getPtr();
    return funcPtr.getEntity().getGraph();
  }
}
