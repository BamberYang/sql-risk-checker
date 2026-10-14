package com.demo.sqlrisk.extractor.lambda;

import com.demo.sqlrisk.extractor.CandidateFile;
import com.demo.sqlrisk.extractor.ChangeClassifier;
import com.demo.sqlrisk.extractor.ExtractContext;
import com.demo.sqlrisk.extractor.ExtractResult;
import com.demo.sqlrisk.extractor.SqlSourceExtractor;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.model.SkippedItem;
import com.demo.sqlrisk.model.StatementSource;
import com.demo.sqlrisk.util.TextUtils;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lambda 类 SQL 提取（详细设计 §9.7，FR-2/D23）：
 * 识别 MyBatis-Plus 链式构造（LambdaQueryWrapper/LambdaUpdateWrapper、lambdaQuery/lambdaUpdate、
 * QueryWrapper().lambda()/Wrapper.lambda()）；实体解析优先级链见 §5.5.4；
 * 语句范围 = 链式构造 ∪ wrapper 执行调用行（DD-7）；方法参数型 wrapper → incomplete=true（D11，还原不完整）。
 * statementId = 文件名(不含路径):行号（根调用行），不参与白名单（FR-6）。
 */
public final class LambdaSqlExtractor implements SqlSourceExtractor {

    private static final Set<String> DIRECT_WRAPPER_TYPES = new HashSet<String>(
            Arrays.asList("LambdaQueryWrapper", "LambdaUpdateWrapper"));

    private static final Set<String> CONVERSION_METHODS = new HashSet<String>(
            Arrays.asList("lambdaQuery", "lambdaUpdate", "lambda"));

    /** Consumer 组方法：实参为子链 λ（嵌套条件组）。 */
    private static final Set<String> CONSUMER_METHODS = new HashSet<String>(
            Arrays.asList("and", "or", "nested", "func"));

    /** 谓词方法基础参数个数（不含布尔条件）；不在表中的按可变参数处理。 */
    private static final Map<String, Integer> BASE_ARITY;

    static {
        Map<String, Integer> map = new HashMap<String, Integer>();
        for (String method : Arrays.asList("eq", "ne", "gt", "ge", "lt", "le", "like", "notLike",
                "likeLeft", "likeRight", "in", "notIn", "inSql", "notInSql",
                "set", "setIncrBy", "setDecrBy")) {
            map.put(method, 2);
        }
        map.put("between", 3);
        map.put("notBetween", 3);
        for (String method : Arrays.asList("isNull", "isNotNull", "exists", "notExists",
                "setSql", "last", "having")) {
            map.put(method, 1);
        }
        BASE_ARITY = Collections.unmodifiableMap(map);
    }

    /** 链式调用排序：按结束位置升序（内层调用先于外层，跨行按行号）。 */
    private static final Comparator<MethodCallExpr> CALL_ORDER =
            Comparator.comparingInt((MethodCallExpr c) -> endLineOf(c))
                    .thenComparingInt(c -> c.getRange().map(r -> r.end.column).orElse(0));

    @Override
    public String name() {
        return "lambda";
    }

    @Override
    public boolean supports(CandidateFile file) {
        return file.path().endsWith(".java");
    }

    @Override
    public ExtractResult extract(CandidateFile file, ExtractContext ctx) {
        String content = file.content();
        if (!content.contains("lambdaQuery") && !content.contains("lambdaUpdate")
                && !content.contains("LambdaQueryWrapper") && !content.contains("LambdaUpdateWrapper")
                && !content.contains(".lambda()")) {
            return ExtractResult.empty(); // 廉价预检：无 Lambda 承载特征
        }
        CompilationUnit cu = parse(file, ctx);
        if (cu == null) {
            return new ExtractResult(Collections.<ExtractedStatement>emptyList(),
                    Collections.singletonList(new SkippedItem(null, file.path(), 0,
                            SkippedItem.Kind.PARSE_FAILED, "解析失败：Java 源码无法解析")));
        }
        EntityIndex entityIndex = ctx == null ? null : ctx.entityIndex();
        Map<String, String> declaredTypes = collectDeclaredTypes(cu);

        List<LambdaChainModel> models = new ArrayList<LambdaChainModel>();
        Set<Node> processedOutermost = new HashSet<Node>(); // 身份去重（JavaParser Node 不覆写 equals）
        for (Expression root : findRoots(cu)) {
            Expression outermost = outermostOf(root);
            if (!processedOutermost.add(outermost)) {
                continue;
            }
            models.addAll(buildModels(root, outermost, cu, declaredTypes, entityIndex, file.path()));
        }
        models.addAll(paramWrapperModels(cu, entityIndex, file.path()));

        List<ExtractedStatement> statements = new ArrayList<ExtractedStatement>();
        for (LambdaChainModel model : models) {
            Optional<ChangeType> change = file.change() == null
                    ? Optional.of(ChangeType.FULL)
                    : ChangeClassifier.classify(file.change(), model.rootLine(), model.rangeEndLine());
            if (!change.isPresent()) {
                continue;
            }
            String statementId = fileNameOf(model.file()) + ":" + model.rootLine();
            String rawText = TextUtils.sliceLines(content, model.rootLine(), model.rangeEndLine());
            statements.add(new ExtractedStatement(statementId, StatementSource.LAMBDA, change.get(),
                    model.file(), model.rootLine(), rawText, model, null));
        }
        return new ExtractResult(statements, Collections.<SkippedItem>emptyList());
    }

    // ---------- 识别 ----------

    private static List<Expression> findRoots(CompilationUnit cu) {
        List<Expression> roots = new ArrayList<Expression>();
        for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
            if (DIRECT_WRAPPER_TYPES.contains(creation.getType().getNameAsString())) {
                roots.add(creation);
            }
        }
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!CONVERSION_METHODS.contains(name)) {
                continue;
            }
            if ("lambda".equals(name) && !call.getScope().isPresent()) {
                continue; // 转换调用必须有接收者
            }
            roots.add(call);
        }
        return roots;
    }

    private static Expression outermostOf(Expression root) {
        Expression current = root;
        while (true) {
            Optional<Node> parent = current.getParentNode();
            if (!parent.isPresent()) {
                return current;
            }
            if (parent.get() instanceof MethodCallExpr) {
                MethodCallExpr call = (MethodCallExpr) parent.get();
                if (call.getScope().isPresent() && call.getScope().get() == current) {
                    current = call;
                    continue;
                }
            }
            return current;
        }
    }

    private static List<LambdaChainModel> buildModels(Expression root, Expression outermost,
                                                      CompilationUnit cu, Map<String, String> declaredTypes,
                                                      EntityIndex entityIndex, String filePath) {
        int rootLine = lineOf(root);
        MethodCallExpr rootCall = root instanceof MethodCallExpr ? (MethodCallExpr) root : null;
        BranchContext branchContext = new BranchContext(); // 控制流分支识别（§9.7-补充，M15）

        // 链式调用（root-first；工厂/转换调用本身不计入条件调用）
        List<LambdaCall> calls = new ArrayList<LambdaCall>();
        for (MethodCallExpr call : chainCalls(root, outermost)) {
            calls.add(toCall(call, branchContext.pathOf(call)));
        }
        int rangeEnd = endLineOf(outermost);

        // 载体变量与后续使用（DD-7：执行调用行纳入语句范围）
        String carrierVar = null;
        Optional<Node> parent = outermost.getParentNode();
        if (parent.isPresent() && parent.get() instanceof VariableDeclarator) {
            VariableDeclarator declarator = (VariableDeclarator) parent.get();
            if (declarator.getInitializer().isPresent() && declarator.getInitializer().get() == outermost) {
                carrierVar = declarator.getNameAsString();
            }
        }
        if (carrierVar != null) {
            Optional<MethodDeclaration> container = outermost.findAncestor(MethodDeclaration.class);
            if (container.isPresent()) {
                List<MethodCallExpr> usages = new ArrayList<MethodCallExpr>();
                for (MethodCallExpr call : container.get().findAll(MethodCallExpr.class)) {
                    if (chainReaches(call, carrierVar) && lineOf(call) > rootLine) {
                        usages.add(call);
                    } else {
                        for (Expression arg : call.getArguments()) {
                            if (arg instanceof NameExpr && ((NameExpr) arg).getNameAsString().equals(carrierVar)) {
                                rangeEnd = Math.max(rangeEnd, endLineOf(call)); // 执行调用（mapper.selectList(w)）
                            }
                        }
                    }
                }
                Collections.sort(usages, CALL_ORDER);
                for (MethodCallExpr usage : usages) {
                    calls.add(toCall(usage, branchContext.pathOf(usage)));
                    rangeEnd = Math.max(rangeEnd, endLineOf(usage));
                }
            }
        } else if (parent.isPresent() && parent.get() instanceof MethodCallExpr) {
            rangeEnd = Math.max(rangeEnd, endLineOf((MethodCallExpr) parent.get())); // 内联链作为实参
        }

        String entityTypeName = resolveEntity(root, rootCall, carrierVar, declaredTypes, entityIndex);
        boolean updateWrapper = isUpdateWrapper(root, rootCall, carrierVar, declaredTypes);
        LambdaChainModel model = new LambdaChainModel(filePath, rootLine, rangeEnd, entityTypeName,
                calls, false, updateWrapper, branchContext.groups());
        List<LambdaChainModel> out = new ArrayList<LambdaChainModel>(1);
        out.add(model);
        return out;
    }

    /** UPDATE 链判定：LambdaUpdateWrapper / lambdaUpdate / set* 方法族；缺省按查询链。 */
    private static boolean isUpdateWrapper(Expression root, MethodCallExpr rootCall, String carrierVar,
                                           Map<String, String> declaredTypes) {
        if (root instanceof ObjectCreationExpr) {
            String type = ((ObjectCreationExpr) root).getType().getNameAsString();
            if ("LambdaUpdateWrapper".equals(type)) {
                return true;
            }
            if ("LambdaQueryWrapper".equals(type)) {
                return false;
            }
        }
        if (rootCall != null) {
            String name = rootCall.getNameAsString();
            if ("lambdaUpdate".equals(name)) {
                return true;
            }
            if ("lambdaQuery".equals(name)) {
                return false;
            }
            Optional<Expression> scope = rootCall.getScope();
            if (scope.isPresent() && scope.get() instanceof ObjectCreationExpr) {
                return ((ObjectCreationExpr) scope.get()).getType().getNameAsString().contains("Update");
            }
            if (scope.isPresent() && scope.get() instanceof NameExpr) {
                String declared = declaredTypes.get(((NameExpr) scope.get()).getNameAsString());
                return declared != null && declared.startsWith("LambdaUpdateWrapper");
            }
        }
        if (carrierVar != null) {
            String declared = declaredTypes.get(carrierVar);
            if (declared != null) {
                return declared.startsWith("LambdaUpdateWrapper");
            }
        }
        return false;
    }

    /** 链上调用（自 root 起向外，含 root 后的各级；root 为 MethodCallExpr 时排除其自身）。 */
    private static List<MethodCallExpr> chainCalls(Expression root, Expression outermost) {
        List<MethodCallExpr> reversed = new ArrayList<MethodCallExpr>();
        Expression current = outermost;
        while (current instanceof MethodCallExpr) {
            MethodCallExpr call = (MethodCallExpr) current;
            reversed.add(call);
            Optional<Expression> scope = call.getScope();
            if (!scope.isPresent()) {
                break;
            }
            current = scope.get();
        }
        Collections.reverse(reversed);
        List<MethodCallExpr> out = new ArrayList<MethodCallExpr>();
        for (MethodCallExpr call : reversed) {
            if (call != root) {
                out.add(call);
            }
        }
        return out;
    }

    // ---------- 实体解析（优先级链 §5.5.4） ----------

    private static String resolveEntity(Expression root, MethodCallExpr rootCall, String carrierVar,
                                        Map<String, String> declaredTypes, EntityIndex entityIndex) {
        // ① 显式泛型 / ② Class 字面量参数
        String entity = explicitEntityOf(root, rootCall);
        // ③ 变量声明类型（钻石语法 new LambdaQueryWrapper<>() 时）
        if (entity == null && carrierVar != null) {
            entity = firstTypeArg(declaredTypes.get(carrierVar));
        }
        // ④ 调用接收者类型（mapper/service 泛型反解）
        if (entity == null && rootCall != null) {
            Optional<Expression> scope = rootCall.getScope();
            if (scope.isPresent() && scope.get() instanceof NameExpr) {
                String receiver = ((NameExpr) scope.get()).getNameAsString();
                if (!"Wrappers".equals(receiver)) {
                    entity = entityOfReceiver(receiver, declaredTypes, entityIndex);
                }
            } else if (!scope.isPresent()) {
                // 隐式 this（ServiceImpl 子类内直接调用 lambdaQuery()）
                Optional<ClassOrInterfaceDeclaration> enclosing =
                        rootCall.findAncestor(ClassOrInterfaceDeclaration.class);
                if (enclosing.isPresent() && entityIndex != null) {
                    entity = entityIndex.entityTypeOf(enclosing.get().getNameAsString()).orElse(null);
                }
            }
        }
        return entity; // 解析失败 → null（表名占位，D13）
    }

    private static String explicitEntityOf(Expression root, MethodCallExpr rootCall) {
        if (root instanceof ObjectCreationExpr) {
            NodeList<Type> typeArgs = ((ObjectCreationExpr) root).getType().getTypeArguments().orElse(null);
            if (typeArgs != null && !typeArgs.isEmpty()) {
                return typeArgs.get(0).asString();
            }
        }
        if (rootCall != null) {
            NodeList<Type> callArgs = rootCall.getTypeArguments().orElse(null);
            if (callArgs != null && !callArgs.isEmpty()) {
                return callArgs.get(0).asString();
            }
            // new QueryWrapper<X>().lambda() / Wrapper.lambda()：泛型在转换调用的接收者构造上
            Optional<Expression> scope = rootCall.getScope();
            if (scope.isPresent() && scope.get() instanceof ObjectCreationExpr) {
                NodeList<Type> creationArgs =
                        ((ObjectCreationExpr) scope.get()).getType().getTypeArguments().orElse(null);
                if (creationArgs != null && !creationArgs.isEmpty()) {
                    return creationArgs.get(0).asString();
                }
            }
            for (Expression arg : rootCall.getArguments()) {
                if (arg instanceof ClassExpr) {
                    return ((ClassExpr) arg).getType().asString();
                }
            }
        }
        return null;
    }

    private static String entityOfReceiver(String receiver, Map<String, String> declaredTypes,
                                           EntityIndex entityIndex) {
        String receiverType = declaredTypes.get(receiver);
        if (receiverType != null) {
            String direct = firstTypeArg(receiverType); // BaseMapper<Order> 变量：直接取声明泛型
            if (direct != null && entityIndex != null && entityIndex.entity(direct).isPresent()) {
                return direct;
            }
            if (entityIndex != null) {
                Optional<String> viaMapper = entityIndex.entityTypeOf(receiverType);
                if (viaMapper.isPresent()) {
                    return viaMapper.get();
                }
            }
            return null;
        }
        if (entityIndex != null) {
            return entityIndex.entityTypeOf(receiver).orElse(null);
        }
        return null;
    }

    /** 声明类型文本的首个泛型实参：LambdaQueryWrapper&lt;Order&gt; → Order、ServiceImpl&lt;M, Order&gt; → M（调用方判定）。 */
    static String firstTypeArg(String declaredTypeText) {
        if (declaredTypeText == null) {
            return null;
        }
        int lt = declaredTypeText.indexOf('<');
        if (lt < 0) {
            return null;
        }
        int end = declaredTypeText.indexOf('>', lt);
        int comma = declaredTypeText.indexOf(',', lt);
        if (comma > 0 && (end < 0 || comma < end)) {
            end = comma;
        }
        if (end < 0) {
            return null;
        }
        String arg = declaredTypeText.substring(lt + 1, end).trim();
        return arg.isEmpty() ? null : arg;
    }

    // ---------- 参数型 wrapper（D11：还原不完整） ----------

    private static List<LambdaChainModel> paramWrapperModels(CompilationUnit cu, EntityIndex entityIndex,
                                                             String filePath) {
        List<LambdaChainModel> out = new ArrayList<LambdaChainModel>();
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            for (Parameter parameter : method.getParameters()) {
                if (!parameter.getType().isClassOrInterfaceType()) {
                    continue;
                }
                ClassOrInterfaceType type = parameter.getType().asClassOrInterfaceType();
                if (!DIRECT_WRAPPER_TYPES.contains(type.getNameAsString())) {
                    continue;
                }
                List<MethodCallExpr> usages = new ArrayList<MethodCallExpr>();
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if (chainReaches(call, parameter.getNameAsString())) {
                        usages.add(call);
                    }
                }
                if (usages.isEmpty()) {
                    continue;
                }
                Collections.sort(usages, CALL_ORDER);
                List<LambdaCall> calls = new ArrayList<LambdaCall>();
                int rootLine = lineOf(usages.get(0));
                int rangeEnd = rootLine;
                for (MethodCallExpr usage : usages) {
                    calls.add(toCall(usage));
                    rangeEnd = Math.max(rangeEnd, endLineOf(usage));
                }
                String entity = type.getTypeArguments().isPresent() && !type.getTypeArguments().get().isEmpty()
                        ? type.getTypeArguments().get().get(0).asString()
                        : null;
                out.add(new LambdaChainModel(filePath, rootLine, rangeEnd, entity, calls, true,
                        "LambdaUpdateWrapper".equals(type.getNameAsString())));
            }
        }
        return out;
    }

    // ---------- 调用与参数 ----------

    static LambdaCall toCall(MethodCallExpr call) {
        return toCall(call, Collections.<LambdaBranchRef>emptyList());
    }

    static LambdaCall toCall(MethodCallExpr call, List<LambdaBranchRef> branchPath) {
        String method = call.getNameAsString();
        NodeList<Expression> rawArgs = call.getArguments();
        boolean runtimeConditional = false;
        int start = 0;
        Integer expected = BASE_ARITY.get(method);
        boolean conditionalForm = false;
        if (expected != null) {
            conditionalForm = rawArgs.size() == expected + 1;
        } else if (!rawArgs.isEmpty() && rawArgs.get(0) instanceof BooleanLiteralExpr) {
            conditionalForm = true; // 可变参数/Consumer 形式：首参为布尔字面量
        }
        if (conditionalForm) {
            start = 1;
            Expression condition = rawArgs.get(0);
            boolean literalTrue = condition instanceof BooleanLiteralExpr
                    && ((BooleanLiteralExpr) condition).getValue();
            if (!literalTrue) {
                runtimeConditional = true; // 保守策略：运行期条件视为不成立 → 渲染期整体丢弃（FR-5）
            }
        }
        List<LambdaArg> args = new ArrayList<LambdaArg>();
        for (int i = start; i < rawArgs.size(); i++) {
            args.add(toArg(rawArgs.get(i)));
        }
        List<LambdaCall> nested = Collections.emptyList();
        if (CONSUMER_METHODS.contains(method)) {
            for (int i = start; i < rawArgs.size(); i++) {
                if (rawArgs.get(i) instanceof LambdaExpr) {
                    nested = nestedCalls((LambdaExpr) rawArgs.get(i));
                    break;
                }
            }
        }
        return new LambdaCall(method, lineOf(call), args, runtimeConditional, nested, branchPath);
    }

    /** Consumer 组内部调用（以 λ 参数为链根，递归支持多层嵌套；组内不再穿透到更深 λ）。 */
    private static List<LambdaCall> nestedCalls(LambdaExpr lambda) {
        if (lambda.getParameters().size() != 1) {
            return Collections.emptyList();
        }
        String paramName = lambda.getParameter(0).getNameAsString();
        List<MethodCallExpr> calls = new ArrayList<MethodCallExpr>();
        for (MethodCallExpr inner : lambda.findAll(MethodCallExpr.class)) {
            if (chainReaches(inner, paramName)) {
                calls.add(inner);
            }
        }
        Collections.sort(calls, CALL_ORDER);
        List<LambdaCall> out = new ArrayList<LambdaCall>();
        for (MethodCallExpr inner : calls) {
            out.add(toCall(inner));
        }
        return out;
    }

    static LambdaArg toArg(Expression expr) {
        if (expr instanceof MethodReferenceExpr) {
            return new LambdaArg(LambdaArg.Kind.S_FUNCTION, ((MethodReferenceExpr) expr).getIdentifier());
        }
        if (expr instanceof LambdaExpr) {
            Statement bodyStmt = ((LambdaExpr) expr).getBody();
            Expression body = bodyStmt instanceof ExpressionStmt
                    ? ((ExpressionStmt) bodyStmt).getExpression()
                    : null;
            if (body instanceof MethodCallExpr) {
                return new LambdaArg(LambdaArg.Kind.S_FUNCTION, ((MethodCallExpr) body).getNameAsString());
            }
            return new LambdaArg(LambdaArg.Kind.OTHER, expr.toString());
        }
        if (expr instanceof ClassExpr) {
            return new LambdaArg(LambdaArg.Kind.CLASS_LITERAL, ((ClassExpr) expr).getType().asString());
        }
        if (expr instanceof StringLiteralExpr) {
            return new LambdaArg(LambdaArg.Kind.STRING_LITERAL, ((StringLiteralExpr) expr).asString());
        }
        if (expr instanceof BooleanLiteralExpr && ((BooleanLiteralExpr) expr).getValue()) {
            return new LambdaArg(LambdaArg.Kind.BOOLEAN_LITERAL_TRUE, "true");
        }
        return new LambdaArg(LambdaArg.Kind.OTHER, expr.toString());
    }

    // ---------- 工具 ----------

    /** 作用域链是否以指定变量结束（w.eq().or() 的各级调用均可达 w）。 */
    static boolean chainReaches(Expression expr, String varName) {
        Expression current = expr;
        while (current instanceof MethodCallExpr) {
            Optional<Expression> scope = ((MethodCallExpr) current).getScope();
            if (!scope.isPresent()) {
                return false;
            }
            current = scope.get();
        }
        return current instanceof NameExpr && ((NameExpr) current).getNameAsString().equals(varName);
    }

    private static Map<String, String> collectDeclaredTypes(CompilationUnit cu) {
        Map<String, String> declared = new LinkedHashMap<String, String>();
        for (VariableDeclarator variable : cu.findAll(VariableDeclarator.class)) {
            declared.putIfAbsent(variable.getNameAsString(), variable.getType().asString());
        }
        for (Parameter parameter : cu.findAll(Parameter.class)) {
            declared.putIfAbsent(parameter.getNameAsString(), parameter.getType().asString());
        }
        return declared;
    }

    private static CompilationUnit parse(CandidateFile file, ExtractContext ctx) {
        if (ctx != null && ctx.sourceIndex() != null) {
            Optional<CompilationUnit> cached = ctx.sourceIndex().byPath(file.path());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        try {
            return StaticJavaParser.parse(file.content());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int lineOf(Node node) {
        return node.getRange().map(r -> r.begin.line).orElse(0);
    }

    private static int endLineOf(Node node) {
        return node.getRange().map(r -> r.end.line).orElse(0);
    }

    private static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    // ---------- 控制流分支识别（详细设计 §9.7-补充，M15） ----------

    /**
     * 分支组注册表（每个链模型独立）。组号 = 首次命中顺序（调用按行序遍历 → 确定性）。
     *
     * <p>映射（对齐 XML 语义）：if/else 与 switch/case → {@code <choose>}（每分支一骨架，渲染期枚举）；
     * 无 else 的 if 与循环体 → {@code <if>}（保守视为不成立：渲染期丢弃 + 溯源记录）。</p>
     */
    private static final class BranchContext {

        private final Map<Node, Integer> groupIds = new IdentityHashMap<Node, Integer>();
        private final Map<Node, LambdaBranchGroup> groupsByNode = new LinkedHashMap<Node, LambdaBranchGroup>();
        private final Map<SwitchEntry, Integer> switchPathIndex = new IdentityHashMap<SwitchEntry, Integer>();

        List<LambdaBranchGroup> groups() {
            return new ArrayList<LambdaBranchGroup>(groupsByNode.values());
        }

        /** 调用的分支坐标（外层→内层；无控制流祖先为空）。 */
        List<LambdaBranchRef> pathOf(Node call) {
            List<LambdaBranchRef> reversed = new ArrayList<LambdaBranchRef>();
            Node current = call;
            Optional<Node> parent = current.getParentNode();
            while (parent.isPresent() && !(parent.get() instanceof MethodDeclaration)) {
                Node node = parent.get();
                if (node instanceof IfStmt) {
                    reversed.add(refForIf((IfStmt) node, current));
                } else if (node instanceof SwitchStmt) {
                    reversed.add(refForSwitch((SwitchStmt) node, current));
                } else if (isLoop(node)) {
                    reversed.add(new LambdaBranchRef(registerGroup(node,
                            Collections.singletonList("loop-skip")), LambdaBranchRef.NEVER_TAKEN));
                }
                current = node;
                parent = current.getParentNode();
            }
            Collections.reverse(reversed);
            return reversed;
        }

        private LambdaBranchRef refForIf(IfStmt ifStmt, Node childUnderIf) {
            int groupId = registerGroup(ifStmt, ifStmt.getElseStmt().isPresent()
                    ? Arrays.asList("if-then", "if-else")
                    : Collections.singletonList("if-skip"));
            if (!ifStmt.getElseStmt().isPresent()) {
                return new LambdaBranchRef(groupId, LambdaBranchRef.NEVER_TAKEN);
            }
            return new LambdaBranchRef(groupId, childUnderIf == ifStmt.getThenStmt() ? 0 : 1);
        }

        private LambdaBranchRef refForSwitch(SwitchStmt switchStmt, Node childUnderSwitch) {
            int groupId = registerSwitchGroup(switchStmt);
            Integer pathIndex = switchPathIndex.get(childUnderSwitch);
            return new LambdaBranchRef(groupId,
                    pathIndex == null ? LambdaBranchRef.NEVER_TAKEN : pathIndex);
        }

        private int registerSwitchGroup(SwitchStmt switchStmt) {
            Integer existing = groupIds.get(switchStmt);
            if (existing != null) {
                return existing;
            }
            int id = groupsByNode.size();
            List<String> labels = new ArrayList<String>();
            List<String> pending = new ArrayList<String>(); // 空条目落穿：并入后继条目
            boolean hasDefault = false;
            List<SwitchEntry> entries = switchStmt.getEntries();
            for (int i = 0; i < entries.size(); i++) {
                SwitchEntry entry = entries.get(i);
                boolean last = i == entries.size() - 1;
                if (isDefault(entry)) {
                    hasDefault = true;
                }
                if (entry.getStatements().isEmpty() && !last) {
                    pending.add(entryLabel(entry));
                    continue;
                }
                List<String> names = new ArrayList<String>(pending);
                names.add(entryLabel(entry));
                pending.clear();
                switchPathIndex.put(entry, labels.size());
                labels.add(String.join(" + ", names));
            }
            if (!hasDefault) {
                labels.add("switch-none"); // 无 default：全部落空为合法运行路径（对齐 choose 无 otherwise）
            }
            groupIds.put(switchStmt, id);
            groupsByNode.put(switchStmt, new LambdaBranchGroup(id, LambdaBranchGroup.Kind.SWITCH, labels));
            return id;
        }

        private int registerGroup(Node node, List<String> labels) {
            Integer existing = groupIds.get(node);
            if (existing != null) {
                return existing;
            }
            int id = groupsByNode.size();
            groupIds.put(node, id);
            groupsByNode.put(node, new LambdaBranchGroup(id, LambdaBranchGroup.Kind.IF, labels));
            return id;
        }

        private static boolean isLoop(Node node) {
            return node instanceof ForStmt || node instanceof ForEachStmt
                    || node instanceof WhileStmt || node instanceof DoStmt;
        }

        private static boolean isDefault(SwitchEntry entry) {
            return entry.getLabels().isEmpty();
        }

        private static String entryLabel(SwitchEntry entry) {
            if (entry.getLabels().isEmpty()) {
                return "default";
            }
            List<String> parts = new ArrayList<String>();
            for (Expression label : entry.getLabels()) {
                parts.add(label.toString());
            }
            return "case " + String.join(", ", parts);
        }
    }
}
