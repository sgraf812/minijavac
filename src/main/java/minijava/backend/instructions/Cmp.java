package minijava.backend.instructions;

import static com.google.common.collect.Lists.newArrayList;

import minijava.backend.operands.MemoryOperand;
import minijava.backend.operands.Operand;

public class Cmp extends CodeBlockInstruction {
  public final Operand left;
  public final Operand right;

  // As with Test, this modifies the FLAGS register. Not sure if we should also model it through
  // a register constraint.
  public Cmp(Operand left, Operand right) {
    super(newArrayList(left, right), newArrayList());
    this.left = left;
    this.right = right;
    if (!(right instanceof MemoryOperand)) {
      setMayBeMemory(left);
    }
  }

  @Override
  public void accept(Visitor visitor) {
    visitor.visit(this);
  }
}
