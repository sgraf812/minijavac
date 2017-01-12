package minijava.ir.optimize;

import firm.BackEdges;
import firm.Graph;
import firm.Mode;
import firm.TargetValue;
import firm.bindings.binding_ircons;
import firm.nodes.Bad;
import firm.nodes.Block;
import firm.nodes.Cond;
import firm.nodes.Const;
import firm.nodes.Jmp;
import firm.nodes.Node;
import firm.nodes.NodeVisitor;
import firm.nodes.Proj;
import minijava.ir.Dominance;
import minijava.ir.utils.GraphUtils;
import minijava.ir.utils.NodeUtils;

/**
 * Replaces {@link Cond} nodes (or more precisely, their accompanying {@link Proj} nodes) with
 * {@link Jmp} nodes, if the condition is constant.
 *
 * <p>The {@link Proj} node that is no longer relevant is replaced with a {@link Bad} node. A
 * subsequent run of an {@link Optimizer} that removes such nodes is required.
 */
public class ConstantControlFlowOptimizer extends NodeVisitor.Default implements Optimizer {

  private Graph graph;
  private boolean hasChanged;

  @Override
  public boolean optimize(Graph graph) {
    this.graph = graph;
    hasChanged = false;
    BackEdges.enable(graph);
    graph.walkTopological(this);
    BackEdges.disable(graph);
    return hasChanged;
  }

  @Override
  public void visit(Cond node) {
    if (node.getSelector() instanceof Const) {
      TargetValue condition = ((Const) node.getSelector()).getTarval();
      NodeUtils.CondProjs projs = NodeUtils.determineProjectionNodes(node);

      Node delete, alwaysTake;
      if (condition.equals(TargetValue.getBTrue())) {
        delete = projs.false_;
        alwaysTake = projs.true_;
      } else {
        delete = projs.true_;
        alwaysTake = projs.false_;
      }

      Block block = (Block) node.getBlock();
      boolean endWasDominated = Dominance.dominates(block, graph.getEndBlock());

      hasChanged = true;
      Graph.exchange(alwaysTake, graph.newJmp(node.getBlock()));
      Graph.exchange(delete, graph.newBad(Mode.getANY()));

      boolean endConnectedToStart = GraphUtils.areConnected(graph.getEnd(), graph.getStart());

      if (endWasDominated && !endConnectedToStart) {
        // We just cut loose the last edge to the end block, meaning this is some kind of endless loop.
        binding_ircons.keep_alive(block.ptr);
      }
    }
  }
}
