package minijava.ir;

import java.util.ArrayList;
import java.util.List;
import minijava.ast.Block;
import minijava.ast.BlockStatement;
import minijava.ast.Expression;
import minijava.ast.Statement;

public class NumberOfLocalVariablesVisitor
    implements Block.Visitor<Integer>,
        Expression.Visitor<Integer>,
        BlockStatement.Visitor<Integer> {
  @Override
  public Integer visitBlock(Block that) {
    int c = 0;
    List<Integer> localVarNums = new ArrayList<>();
    for (BlockStatement statement : that.statements) {
      if (statement instanceof Block
          || statement instanceof Statement.While
          || statement instanceof Statement.If) {
        /* Statements that open a new scope */
        localVarNums.add(c + statement.acceptVisitor(this));
      } else {
        c += statement.acceptVisitor(this);
      }
    }
    localVarNums.add(c);
    return localVarNums.stream().max(Integer::compare).get();
  }

  @Override
  public Integer visitEmpty(Statement.Empty that) {
    return 0;
  }

  @Override
  public Integer visitIf(Statement.If that) {
    int elseNum = 0;
    if (that.else_.isPresent()) {
      elseNum = that.else_.get().acceptVisitor(this);
    }
    return Math.max(that.then.acceptVisitor(this), elseNum);
  }

  @Override
  public Integer visitExpressionStatement(Statement.ExpressionStatement that) {
    return 0;
  }

  @Override
  public Integer visitWhile(Statement.While that) {
    return that.body.acceptVisitor(this);
  }

  @Override
  public Integer visitReturn(Statement.Return that) {
    return 0;
  }

  @Override
  public Integer visitBinaryOperator(Expression.BinaryOperator that) {
    return 0;
  }

  @Override
  public Integer visitUnaryOperator(Expression.UnaryOperator that) {
    return 0;
  }

  @Override
  public Integer visitMethodCall(Expression.MethodCall that) {
    return 0;
  }

  @Override
  public Integer visitFieldAccess(Expression.FieldAccess that) {
    return 0;
  }

  @Override
  public Integer visitArrayAccess(Expression.ArrayAccess that) {
    return 0;
  }

  @Override
  public Integer visitNewObject(Expression.NewObject that) {
    return 0;
  }

  @Override
  public Integer visitNewArray(Expression.NewArray that) {
    return 0;
  }

  @Override
  public Integer visitVariable(Expression.Variable that) {
    return 0;
  }

  @Override
  public Integer visitBooleanLiteral(Expression.BooleanLiteral that) {
    return 0;
  }

  @Override
  public Integer visitIntegerLiteral(Expression.IntegerLiteral that) {
    return 0;
  }

  @Override
  public Integer visitReferenceTypeLiteral(Expression.ReferenceTypeLiteral that) {
    return 0;
  }

  @Override
  public Integer visitVariable(BlockStatement.Variable that) {
    return 1;
  }
}
