package minijava.backend.deconstruction;

import static com.google.common.collect.Lists.newArrayList;
import static org.jooq.lambda.Seq.seq;

import firm.Relation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import minijava.backend.allocation.AllocationResult;
import minijava.backend.block.CodeBlock;
import minijava.backend.block.PhiFunction;
import minijava.backend.instructions.Instruction;
import minijava.backend.instructions.Jcc;
import minijava.backend.instructions.Jmp;
import minijava.backend.instructions.Label;
import minijava.backend.instructions.Ret;
import minijava.backend.lifetime.BlockPosition;
import minijava.backend.lifetime.LifetimeInterval;
import minijava.backend.operands.Operand;

public class SsaDeconstruction {
  private final List<CodeBlock> linearization;
  private final AllocationResult allocationResult;
  private final Map<CodeBlock, List<Instruction>> resolvedBlocks = new HashMap<>();

  // For caching; O(V) instead of O(E)
  private final Map<CodeBlock, List<LifetimeInterval>> liveAtBegin = new HashMap<>();

  public SsaDeconstruction(List<CodeBlock> linearization, AllocationResult allocationResult) {
    this.linearization = linearization;
    this.allocationResult = allocationResult;
  }

  private List<Instruction> assembleInstructionList() {
    lowerVirtualOperandsAndInsertSpills();
    resolvePhisAndSplitIntervals();
    return flattenIntoInstructionList();
  }

  private void lowerVirtualOperandsAndInsertSpills() {
    for (CodeBlock block : linearization) {
      resolvedBlocks.put(block, InstructionListLowerer.lowerBlock(block, allocationResult));
    }
  }

  private void resolvePhisAndSplitIntervals() {
    // For each control flow edge...
    Map<CodeBlock, Set<Move>> toResolve = new HashMap<>();

    for (CodeBlock pred : linearization) {
      Set<CodeBlock> successors = pred.exit.getSuccessors();
      boolean predHasMultipleSuccs = successors.size() > 1;
      for (CodeBlock succ : successors) {
        boolean succHasPhis = succ.phis.size() > 0;
        // This check could be more precise if we stored the Preds in the CodeBlock, which we don't.
        // Critical edges without Phis might still slip through, causing trouble for split intervals.
        assert !succHasPhis || !predHasMultipleSuccs
            : "Found a critical edge from " + pred + " to " + succ;

        // Resolving moves are also necessary at the begin (or end) of an else branch,
        // when an interval was split in the then branch, for example.
        // Because the blocks inserted to split critical edges conflict are where the preds need
        // resolving moves, we have the old problem of having to insert resolving moves in blocks
        // with multiple successors, which is really hard to get right.
        // Therefore we retain the necessary moves, so that they are inserted in target block instead.
        // Which should be fine, since they *should* be empty anyway.
        CodeBlock resolveWhere = pred;
        if (predHasMultipleSuccs) {
          assert succ.instructions.isEmpty() : "Can't place resolving moves into a non-empty succ";
          // We have to check that this indeed the only edge over which succ is reachable.
          // Checking phis is the closest we can get.
          assert succ.phis.isEmpty() : "Can't place resolving moves in a block with multiple preds";
          resolveWhere = succ;
        }

        resolveControlFlowEdge(
            pred, succ, toResolve.computeIfAbsent(resolveWhere, k -> new HashSet<>()));
      }
    }

    for (CodeBlock block : linearization) {
      Set<Move> moves = toResolve.get(block);
      if (moves == null) {
        continue;
      }
      resolvedBlocks.get(block).addAll(MoveResolver.resolveMoves(moves));
    }
  }

  private List<LifetimeInterval> liveAtBegin(CodeBlock succ) {
    return liveAtBegin.computeIfAbsent(
        succ, k -> allocationResult.liveIntervalsAt(BlockPosition.beginOf(k)));
  }

  private void resolveControlFlowEdge(CodeBlock pred, CodeBlock succ, Set<Move> toResolve) {
    // For each interval live at the begin of succ
    BlockPosition endOfPred = BlockPosition.endOf(pred);
    BlockPosition beginOfSucc = BlockPosition.beginOf(succ);
    for (LifetimeInterval li : liveAtBegin(succ)) {
      boolean isPhiOfSucc = isDefinedByPhi(succ, li);
      Operand dest = allocationResult.hardwareOperandAt(li.register, beginOfSucc);
      Operand src;
      if (isPhiOfSucc) {
        // li is the interval of a Phi of succ. We don't go through the virtual Phis of succ, but to the allocated
        // Phis at the Label instruction (which always is the first instruction of a lowered block).
        Label label = (Label) resolvedBlocks.get(succ).get(0);
        PhiFunction def =
            seq(label.physicalPhis).filter(phi -> phi.output.equals(dest)).findFirst().get();
        src = def.inputs.get(pred);
      } else {
        src = allocationResult.hardwareOperandAt(li.register, endOfPred);
      }

      if (!src.equals(dest)) {
        assert !seq(toResolve).map(m -> m.dest).contains(dest);
        toResolve.add(new Move(src, dest));
      }
    }
  }

  private boolean isDefinedByPhi(CodeBlock succ, LifetimeInterval li) {
    return succ.equals(li.firstBlock())
        // ... and is the first interval of fall splits of that virtual register, e.g. the defining
        && allocationResult.splitLifetimes.get(li.register).get(0).equals(li);
  }

  private List<Instruction> flattenIntoInstructionList() {
    List<Instruction> instructions = new ArrayList<>();
    for (int i = 0; i < linearization.size(); i++) {
      CodeBlock block = linearization.get(i);
      CodeBlock next = i + 1 < linearization.size() ? linearization.get(i + 1) : null;
      instructions.addAll(resolvedBlocks.get(block));
      instructions.addAll(lowerBlockExit(block, next));
    }
    return instructions;
  }

  /**
   * This is a little more complicated than it needs to be, because we try to fall through to
   * successor blocks if possible.
   */
  private ArrayList<Instruction> lowerBlockExit(CodeBlock block, CodeBlock next) {
    return block.exit.match(
        zero -> newArrayList(new Ret()),
        one ->
            one.target.equals(next)
                ? newArrayList() // No jmp needed, just fall through
                : newArrayList(new Jmp(one.target.label)),
        two ->
            two.falseTarget.equals(next)
                ? newArrayList(
                    new Jcc(two.trueTarget.label, two.relation)) // We can fall through to false
                : two.trueTarget.equals(next)
                    ? newArrayList(
                        new Jcc(
                            two.falseTarget.label,
                            negatedWithoutUnordered(two.relation))) // fall through to true
                    : newArrayList(
                        new Jcc(two.trueTarget.label, two.relation),
                        new Jmp(two.falseTarget.label)));
  }

  private static Relation negatedWithoutUnordered(Relation relation) {
    return Relation.fromValue(relation.negated().value() & ~Relation.Unordered.value());
  }

  public static List<Instruction> assembleInstructionList(
      List<CodeBlock> linearization, AllocationResult allocationResult) {
    return new SsaDeconstruction(linearization, allocationResult).assembleInstructionList();
  }
}
