package minijava.ir.utils;

import static org.jooq.lambda.tuple.Tuple.tuple;

import firm.Graph;
import firm.nodes.End;
import firm.nodes.Node;
import firm.nodes.Start;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Consumer;
import org.jooq.lambda.tuple.Tuple2;

public class GraphUtils {
  public static Tuple2<Start, End> copyGraph(Graph from, Graph to) {
    CopyWorker worker = new CopyWorker(to);
    return FirmUtils.withoutBackEdges(
        to,
        () -> {
          return tuple(worker.copyNode(from.getStart()), worker.copyNode(from.getEnd()));
        });
  }

  private static class CopyWorker {
    private final Map<Node, Node> mapping = new HashMap<>();
    private final Graph graph;

    CopyWorker(Graph graph) {
      this.graph = graph;
    }

    private <T extends Node> T copyNode(T node) {
      if (mapping.containsKey(node)) {
        return (T) mapping.get(node);
      }
      T copy = (T) graph.copyNode(node);
      mapping.put(node, copy);
      mapping.put(copy, copy); // Just in case some reference was already updated
      if (node.getBlock() != null) {
        copy.setBlock(copyNode(copy.getBlock()));
      }
      final int n = copy.getPredCount();
      for (int i = 0; i < n; ++i) {
        Node pred = copy.getPred(i);
        copy.setPred(i, copyNode(pred));
      }
      return copy;
    }
  }

  /**
   * Walks all graph nodes reachable via predecessor edges from {@param node} and calls {@param
   * onDiscover} and {@param onFinish} in preorder resp. postorder.
   *
   * @param node Seed of the depth-first traversal
   * @param onDiscover Called with reachable nodes in preorder
   * @param onFinish Called with reachable nodes in postorder
   */
  public static void walkFromNodeDepthFirst(
      Node node, Consumer<Node> onDiscover, Consumer<Node> onFinish) {
    walkDFHelper(node, onDiscover, onFinish, new HashSet<>());
  }

  private static void walkDFHelper(
      Node node, Consumer<Node> onDiscover, Consumer<Node> onFinish, HashSet<Node> grey) {
    if (grey.contains(node)) {
      return;
    }
    grey.add(node);
    onDiscover.accept(node);
    if (node.getBlock() != null) {
      walkDFHelper(node.getBlock(), onDiscover, onFinish, grey);
    }
    for (Node pred : node.getPreds()) {
      walkDFHelper(pred, onDiscover, onFinish, grey);
    }
    onFinish.accept(node);
  }
}
