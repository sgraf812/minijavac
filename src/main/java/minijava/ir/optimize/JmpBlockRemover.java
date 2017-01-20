package minijava.ir.optimize;

import static org.jooq.lambda.Seq.seq;

import com.google.common.collect.Iterables;
import firm.BackEdges;
import firm.Graph;
import firm.nodes.Block;
import firm.nodes.Jmp;
import firm.nodes.Node;
import java.util.List;
import java.util.stream.Collectors;
import minijava.ir.utils.FirmUtils;
import minijava.ir.utils.GraphUtils;

/** Removes {@link Block} nodes that contain a single {@link Jmp} node and nothing else. */
public class JmpBlockRemover extends BaseOptimizer {

  @Override
  public boolean optimize(Graph graph) {
    this.graph = graph;
    this.hasChanged = false;
    FirmUtils.withBackEdges(graph, () -> GraphUtils.reversePostOrder(graph).forEach(this::visit));
    return hasChanged;
  }

  @Override
  public void visit(Block block) {
    if (isJmpBlock(block) && hasOnePredecessor(block)) {
      hasChanged = true;
      remove(block);
    }
  }

  private boolean isJmpBlock(Block block) {
    List<Node> nodesInBlock = nodesInBlock(block);
    return nodesInBlock.size() == 1 && nodesInBlock.get(0) instanceof Jmp;
  }

  private List<Node> nodesInBlock(Block block) {
    return seq(BackEdges.getOuts(block)).map(e -> e.node).collect(Collectors.toList());
  }

  private boolean hasOnePredecessor(Block block) {
    return Iterables.size(block.getPreds()) == 1;
  }

  // This implementation is not capable of removing jmp-only blocks with more than one predecessor.
  // To implement this, the Phi nodes in the target block must also be adjusted.
  private void remove(Block block) {
    Jmp jmp = (Jmp) nodesInBlock(block).get(0);
    BackEdges.Edge jmpToTargetEdge = Iterables.getOnlyElement(BackEdges.getOuts(jmp));
    Block jmpTarget = (Block) jmpToTargetEdge.node;
    Node jmpBlockPredecessor = Iterables.getOnlyElement(block.getPreds());
    jmpTarget.setPred(jmpToTargetEdge.pos, jmpBlockPredecessor);
    Graph.killNode(block);
  }
}
