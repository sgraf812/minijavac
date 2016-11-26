package minijava.ir;

import com.beust.jcommander.internal.Nullable;
import firm.*;
import firm.Program;
import firm.Type;
import firm.bindings.binding_ircons;
import firm.nodes.*;
import java.util.*;
import java.util.function.Function;
import minijava.ast.*;
import minijava.ast.Class;
import minijava.ast.Field;
import minijava.ast.Method;
import minijava.util.SourceRange;

/** Emits an intermediate representation for a given minijava Program. */
public class IREmitter
    implements minijava.ast.Program.Visitor<Void>,
        minijava.ast.Type.Visitor<Type>,
        minijava.ast.BasicType.Visitor<Type>,
        minijava.ast.Block.Visitor<Integer>,
        Expression.Visitor<Node>,
        BlockStatement.Visitor<Integer> {

  /** TODO: delete this, irrelevant to the visiting logic */
  private minijava.ast.Program program;

  private Map<Class, ClassType> classTypes = new IdentityHashMap<>();
  private Map<Method, Entity> methods = new IdentityHashMap<>();
  private Map<Field, Entity> fields = new IdentityHashMap<>();
  /**
   * Maps local variable definitions such as parameters and ... local variable definitions to their
   * assigned index. Which is a firm thing.
   */
  private Map<LocalVariable, Integer> localVarIndexes = new IdentityHashMap<>();
  /** The Basic Block graph of the current function. */
  private Graph graph;
  /**
   * Construction is a firm Node factory that makes sure that we don't duplicate expressions, thus
   * making common sub expressions irrepresentable.
   */
  private Construction construction;
  /**
   * Stores the node's value in the current lvar (if there is any). This is crucial for assignment
   * to work. E.g. in the expression x = 5, we analyze the variable expression x and get back its
   * value, which is irrelevant for assignment, since we need the *address of* x. This is where we
   * need storeInCurrentLval, which would store the expression node of the RHS (evaluating to 5) in
   * the address of x.
   *
   * <p>Now, in an ideal world, this variable would be 'Node currentLval', but firm doesn't offer a
   * function for getting the address of a local variable as a Node. So in order to not duplicate a
   * lot of work (e.g. computing array offsets, etc.) in a mechanism without this variable, we
   * abstract actual assignment out into a function.
   */
  @Nullable private Function<Node, Node> storeInCurrentLval;

  private final Type INT_TYPE;
  private final Type BOOLEAN_TYPE;

  public IREmitter(minijava.ast.Program program) {
    this.program = program;
    Firm.init();
    System.out.printf("Firm Version: %1s.%2s\n", Firm.getMajorVersion(), Firm.getMinorVersion());
    INT_TYPE = new PrimitiveType(Mode.getIs());
    BOOLEAN_TYPE = new PrimitiveType(Mode.getBu());
  }

  public void run(boolean outputGraphs) {
    visitProgram(program);
    if (outputGraphs) {
      for (Graph g : Program.getGraphs()) {
        g.check();
        Dump.dumpGraph(g, "");
      }
    }
  }

  public static void main(String[] main_args) {}

  @Override
  public Void visitProgram(minijava.ast.Program that) {
    classTypes.clear();
    methods.clear();
    fields.clear();
    for (Class decl : that.declarations) {
      ClassType classType = new ClassType(decl.name());
      // TODO: Set mangled names with Entity.setLdIdent()
      classTypes.put(decl, classType);
    }
    for (Class decl : that.declarations) {
      for (Field f : decl.fields) {
        fields.put(f, addFieldDecl(f));
      }
      for (Method m : decl.methods) {
        methods.put(m, addMethodDecl(m));
      }
    }
    for (ClassType classType : classTypes.values()) {
      // TODO: Not sure what else needs to be done for layout. Look up the docs
      // Does this even belong here?
      classType.layoutFields();
      classType.finishLayout();
    }
    for (Class klass : that.declarations) {
      klass.methods.forEach(this::emitBody);
    }
    return null;
  }

  private Entity addFieldDecl(Field f) {
    Type type = f.type.acceptVisitor(this);
    ClassType definingClass = classTypes.get(f.definingClass.def);
    Entity fieldEnt = new Entity(definingClass, f.name(), type);
    fieldEnt.setLdIdent(NameMangler.mangleInstanceFieldName(definingClass.getName(), f.name()));
    return fieldEnt;
  }

  /**
   * This will *not* go through the body of the method, just analyze stuff that is needed for
   * constructing an entity.
   */
  private Entity addMethodDecl(Method m) {
    ClassType definingClass = classTypes.get(m.definingClass.def);
    ArrayList<Type> parameterTypes = new ArrayList<>();

    if (m.isStatic) {
      // main has this annoying void parameter hack. Let's compensate
      // for that.
      Type arrayOfString = ptrTo(ptrTo(new PrimitiveType(Mode.getBu())));
      parameterTypes.add(arrayOfString);
    } else {
      // Add the this pointer. It's always parameter 0, so access will be trivial.
      parameterTypes.add(ptrTo(definingClass));
      for (LocalVariable p : m.parameters) {
        // In the body, we need to refer to local variables by index, so we save that mapping.
        parameterTypes.add(p.type.acceptVisitor(this));
      }
    }

    // The visitor returns null if that.returnType was void.
    Type returnType = m.returnType.acceptVisitor(this);
    Type[] returnTypes = returnType == null ? new Type[0] : new Type[] {returnType};

    Type methodType = new MethodType(parameterTypes.toArray(new Type[0]), returnTypes);

    // Set the mangled name
    Entity methodEnt = new Entity(definingClass, m.name(), methodType);
    methodEnt.setLdIdent(NameMangler.mangleMethodName(definingClass.getName(), m.name()));
    return methodEnt;
  }

  private void emitBody(Method m) {
    // graph and construction are irrelevant to anything before or after.
    // It's more like 2 additional parameters to the visitor.

    graph = constructEmptyGraphFromPrototype(m);
    construction = new Construction(graph);

    connectParametersToIRVariables(m);

    m.body.acceptVisitor(this);

    finishGraphAndHandleFallThrough(m);
  }

  private Graph constructEmptyGraphFromPrototype(Method that) {
    // So we got our method prototype from the previous pass. Now for the body
    int locals = that.body.acceptVisitor(new NumberOfLocalVariablesVisitor());
    return new Graph(methods.get(that), that.parameters.size() + locals);
  }

  /**
   * Make the connection between function parameters and local firm variables. firm handles this
   * variable stuff so that it can build up the SSA form later on.
   */
  private void connectParametersToIRVariables(Method that) {
    localVarIndexes.clear();
    if (!that.isStatic) {
      // First a hack for the this parameter. We want it to get allocated index 0, which will be the
      // case if we force its LocalVarIndex first. We do so by allocating an index for a dummy LocalVariable.
      minijava.ast.Type fakeThisType =
          new minijava.ast.Type(new Ref<>(that.definingClass.def), 0, SourceRange.FIRST_CHAR);
      // ... do this just for the allocation effect.
      int thisIdx = getLocalVarIndex(new LocalVariable(fakeThisType, null, SourceRange.FIRST_CHAR));
      // We rely on this when accessing this.
      assert thisIdx == 0;
    }

    Node args = graph.getArgs();
    for (LocalVariable p : that.parameters) {
      // we just made this connection in the loop above
      // Also effectively this should just count up.
      // Also note that we are never trying to access this or the
      int idx = getLocalVarIndex(p);
      // Where is this documented anyway? SimpleIf seems to be the only reference...
      Node param = construction.newProj(args, accessModeForType(p.type), idx);
      construction.setVariable(idx, param);
    }
  }

  /** Finish the graph by adding possible return statements in the case of void */
  private void finishGraphAndHandleFallThrough(Method that) {
    construction.setCurrentBlock(graph.getEndBlock());

    if (!construction.isUnreachable()) {
      // Add an implicit return statement at the end of the block,
      // iff we have return type void. In which case returnTypes has length 0.
      if (that.returnType == minijava.ast.Type.VOID) {
        Node ret = construction.newReturn(construction.getCurrentMem(), new Node[0]);
        construction.setCurrentMem(ret);
        graph.getEndBlock().addPred(ret);
      } else {
        // We can't just conjure a return value of arbitrary type.
        // This must be caught by the semantic pass.
        assert false;
      }
    }

    construction.setUnreachable();
    construction.finish();
  }

  @Override
  public Type visitType(minijava.ast.Type that) {
    Type type = that.basicType.def.acceptVisitor(this);
    if (type == null) {
      // e.g. void
      return null;
    }
    for (int i = 0; i < that.dimension; i++) {
      type = new ArrayType(type, -1);
    }
    return type;
  }

  @Override
  public Type visitVoid(BuiltinType that) {
    return null;
  }

  @Override
  public Type visitInt(BuiltinType that) {
    return INT_TYPE;
  }

  @Override
  public Type visitBoolean(BuiltinType that) {
    return BOOLEAN_TYPE;
  }

  @Override
  public Type visitAny(BuiltinType that) {
    // TODO... not sure how to handle this
    assert false;
    return null;
  }

  @Override
  public Type visitClass(Class that) {
    return classTypes.get(that);
  }

  private Mode accessModeForType(minijava.ast.Type type) {
    if (type.dimension > 0) {
      return Mode.getP();
    }

    switch (type.basicType.name()) {
      case "int":
        return Mode.getIs();
      case "boolean":
        return Mode.getBu();
      default:
        return Mode.getP();
    }
  }

  private PointerType ptrTo(Type type) {
    return new PointerType(type);
  }

  @Override
  public Integer visitBlock(minijava.ast.Block that) {
    for (BlockStatement statement : that.statements) {
      statement.acceptVisitor(this);
    }
    return null;
  }

  @Override
  public Integer visitEmpty(Statement.Empty that) {
    return null;
  }

  @Override
  public Integer visitIf(Statement.If that) {
    // Evaluate condition and set the place for the condition result
    //that.condition.acceptVisitor(this);

    // next week...

    // Conditional Jump Node with the True+False Proj
    return null;
  }

  @Override
  public Integer visitExpressionStatement(Statement.ExpressionStatement that) {
    // We evaluate this just for the side effects, e.g. the memory edges this adds.
    that.expression.acceptVisitor(this);
    return null;
  }

  @Override
  public Integer visitWhile(Statement.While that) {
    // next week
    return null;
  }

  @Override
  public Integer visitReturn(Statement.Return that) {
    List<Node> retVals = new ArrayList<>(1);
    if (that.expression.isPresent()) {
      retVals.add(that.expression.get().acceptVisitor(this));
    }
    Node ret = construction.newReturn(construction.getCurrentMem(), retVals.toArray(new Node[0]));
    Node memNode =
        construction.newProj(
            construction.getCurrentMem(), Mode.getX(), that.expression.isPresent() ? 1 : 0);
    // TODO: do we need to setCurrentMem? If so, what if the return type is void?
    construction.setCurrentMem(memNode);
    graph.getEndBlock().addPred(ret);

    // No code should follow a return statement.
    construction.setUnreachable();

    return null;
  }

  @Override
  public Node visitBinaryOperator(Expression.BinaryOperator that) {
    // Evaluation order demands that we visit the right node first
    // Consider side-effects like assignment: x = (x = 3) + 1; should assign 4 to x,
    // so we have evaluate left after right.
    Node right =
        construction.newConv(that.right.acceptVisitor(this), accessModeForType(that.right.type));
    Node left =
        construction.newConv(that.left.acceptVisitor(this), accessModeForType(that.left.type));

    // Save the store emitter of the left expression (if there's one, e.g. iff it's an lval).
    // See the comments on storeInCurrentLval.
    Function<Node, Node> storeInLeft = storeInCurrentLval;
    assert storeInLeft != null; // This should be true after semantic analysis.

    // This can never produce an lval (an assignable expression)
    storeInCurrentLval = null;

    switch (that.op) {
      case ASSIGN:
        // See the comment on storeInCurrentLval. Assignment is basically outsourced to the
        // resp. expression visitor
        return storeInLeft.apply(right);
      case PLUS:
        return construction.newAdd(left, right);
      case MINUS:
        return construction.newSub(left, right);
      case MULTIPLY:
        return construction.newMul(left, right);
      case DIVIDE:
        return construction.newDiv(
            construction.getCurrentMem(),
            left,
            right,
            binding_ircons.op_pin_state.op_pin_state_exc_pinned);
      case MODULO:
        return construction.newMod(
            construction.getCurrentMem(),
            left,
            right,
            binding_ircons.op_pin_state.op_pin_state_exc_pinned);
      case OR:
        return construction.newOr(left, right);
      case AND:
        return construction.newAnd(left, right);
      case EQ:
        Node cmp = construction.newCmp(left, right, Relation.Equal);
        // TODO: How to project out a byte flag?
        throw new UnsupportedOperationException();
      case NEQ:
        throw new UnsupportedOperationException();
      case LT:
        throw new UnsupportedOperationException();
      case LEQ:
        throw new UnsupportedOperationException();
      case GT:
        throw new UnsupportedOperationException();
      case GEQ:
        throw new UnsupportedOperationException();
      default:
        throw new UnsupportedOperationException();
    }
  }

  @Override
  public Node visitUnaryOperator(Expression.UnaryOperator that) {
    Node expression = that.expression.acceptVisitor(this);

    // This can never produce an lval (an assignable expression)
    storeInCurrentLval = null;

    switch (that.op) {
      case NEGATE:
        return construction.newMinus(expression);
      case NOT:
        return construction.newNot(expression);
      default:
        throw new UnsupportedOperationException();
    }
  }

  @Override
  public Node visitMethodCall(Expression.MethodCall that) {
    if (that.self.type == minijava.ast.Type.SYSTEM_OUT) {
      // TODO: handle a special call to print_int, like
      // it's also done for calloc
    }

    Entity method = methods.get(that.method.def);

    List<Node> args = new ArrayList<>(that.arguments.size() + 1);
    // first argument is always this (static calls are disallowed)
    Node thisVar = construction.getVariable(0, Mode.getP());
    args.add(thisVar);
    for (Expression a : that.arguments) {
      args.add(a.acceptVisitor(this));
    }

    Type returnType = that.method.def.returnType.acceptVisitor(this);
    storeInCurrentLval = null;
    Node f = construction.newMember(thisVar, method);
    // TODO: What about void return values?
    return callFunction(f, args.toArray(new Node[0]), returnType);
  }

  @Override
  public Node visitFieldAccess(Expression.FieldAccess that) {
    // This produces an lval
    Entity field = fields.get(that.field.def);
    Node thisVar = construction.getVariable(0, Mode.getP());
    Node f = construction.newMember(thisVar, field);

    storeInCurrentLval =
        (Node val) -> {
          Node store = construction.newStore(construction.getCurrentMem(), f, val);
          construction.setCurrentMem(construction.newProj(store, Mode.getM(), Store.pnM));
          return val;
        };
    return construction.newMember(thisVar, field);
  }

  @Override
  public Node visitArrayAccess(Expression.ArrayAccess that) {
    Node array = that.array.acceptVisitor(this);
    Node index = that.index.acceptVisitor(this);
    minijava.ast.Type arrayType = that.array.type;
    minijava.ast.Type elementType =
        new minijava.ast.Type(arrayType.basicType, arrayType.dimension - 1, arrayType.range());
    int elementSize = elementType.acceptVisitor(this).getSize();

    // TODO: Use Sel instead
    Node sizeNode = construction.newConst(elementSize, accessModeForType(minijava.ast.Type.INT));
    Node relOffset = construction.newMul(sizeNode, index);
    Node absOffset = construction.newAdd(array, relOffset);
    Mode mode = accessModeForType(elementType);
    storeInCurrentLval =
        (Node val) -> {
          // We store val at the absOffset
          Node store = construction.newStore(construction.getCurrentMem(), absOffset, val);
          construction.setCurrentMem(construction.newProj(store, Mode.getM(), Store.pnM));
          return val;
        };

    // Now just dereference the computed offset
    Node load = construction.newLoad(construction.getCurrentMem(), absOffset, mode);
    construction.setCurrentMem(construction.newProj(load, Mode.getM(), Load.pnM));
    return construction.newProj(load, mode, Load.pnRes);
  }

  @Override
  public Node visitNewObject(Expression.NewObject that) {
    Type type = that.type.acceptVisitor(this);
    storeInCurrentLval = null;
    // See calloc for the rationale behind Mode.getP()
    return calloc(construction.newConst(1, Mode.getP()), type);
  }

  @Override
  public Node visitNewArray(Expression.NewArray that) {
    Type elementType = that.elementType.acceptVisitor(this);
    Node size = that.size.acceptVisitor(this);
    storeInCurrentLval = null;
    return calloc(size, elementType);
  }

  private Node calloc(Node num, Type elementType) {
    // calloc takes two parameters, for the number of elements and the size of each element.
    // both are of type size_t, which is mostly a machine word. So the modes used are just
    // an educated guess.
    // The fact that we called the array length size (which is parameter num to calloc) and
    // that here the element size is called size may be confusing, but whatever, I warned you.
    Type size_t = new PrimitiveType(Mode.getP());
    Node numNode = construction.newConv(num, Mode.getP());
    Node sizeNode = construction.newConst(elementType.getSize(), Mode.getP());
    MethodType callocType =
        new MethodType(new Type[] {size_t, size_t}, new Type[] {ptrTo(elementType)});
    Entity calloc = new Entity(Program.getGlobalType(), "calloc", callocType);
    Node f = construction.newAddress(calloc);
    return callFunction(f, new Node[] {numNode, sizeNode}, elementType);
  }

  private Node callFunction(Node func, Node[] args, Type elementType) {
    Node call = construction.newCall(construction.getCurrentMem(), func, args, ptrTo(elementType));
    construction.setCurrentMem(construction.newProj(call, Mode.getM(), Call.pnM));
    Node result = construction.newProj(call, Mode.getT(), Call.pnTResult);
    return construction.newProj(result, Mode.getP(), 0);
  }

  @Override
  public Node visitVariable(Expression.Variable that) {
    Mode mode = accessModeForType(that.type);
    // This will allocate a new index if necessary.
    int idx = getLocalVarIndex(that.var.def);
    storeInCurrentLval =
        (Node val) -> {
          construction.setVariable(idx, val);
          return val;
        };
    return construction.getVariable(idx, mode);
  }

  @Override
  public Node visitBooleanLiteral(Expression.BooleanLiteral that) {
    storeInCurrentLval = null;
    return construction.newConst(
        that.literal ? 1 : 0, accessModeForType(minijava.ast.Type.BOOLEAN));
  }

  @Override
  public Node visitIntegerLiteral(Expression.IntegerLiteral that) {
    // TODO: the 0x80000000 case
    int lit = Integer.parseInt(that.literal);
    storeInCurrentLval = null;
    return construction.newConst(lit, accessModeForType(minijava.ast.Type.INT));
  }

  @Override
  public Node visitReferenceTypeLiteral(Expression.ReferenceTypeLiteral that) {
    storeInCurrentLval = null;
    switch (that.name()) {
      case "this":
        // access parameter 0 as a pointer, that's where this is to be found
        return construction.getVariable(0, Mode.getP());
      case "null":
        return construction.newConst(0, Mode.getP());
      case "System.out":
        // TODO: Don't know how to handle this. We should probably
        // catch this case before we can reference System.out, like we did in
        // SemanticAnalyzer
        return null;
      default:
        throw new UnsupportedOperationException(); // This should be exhaustive.
    }
  }

  @Override
  public Integer visitVariable(BlockStatement.Variable that) {
    int idx = getLocalVarIndex(that);
    if (that.rhs.isPresent()) {
      Node rhs = that.rhs.get().acceptVisitor(this);
      construction.setVariable(getLocalVarIndex(that), rhs);
    }

    return null;
  }

  /**
   * This will do the mapping from local variables to their firm variable indices. It will compute
   * new indices as needed, so we need to process new variables in the exact order they should be
   * allocated. Although the exact mapping is rather an implementation detail. E.g. mapping the
   * first parameter to index 5 instead of index 1 isn't bad if we always use 5 when we refer to the
   * first parameter.
   *
   * <p>So, whenever computing variable indices, use this function.
   */
  private int getLocalVarIndex(LocalVariable var) {
    if (localVarIndexes.containsKey(var)) {
      return localVarIndexes.get(var);
    } else {
      // allocate a new index
      int free = localVarIndexes.size();
      localVarIndexes.put(var, free);
      return free;
    }
  }
}