package com.demo.sqlrisk.extractor.lambda;

import com.demo.sqlrisk.diff.FileChange;
import com.demo.sqlrisk.diff.Hunk;
import com.demo.sqlrisk.extractor.CandidateFile;
import com.demo.sqlrisk.extractor.ExtractContext;
import com.demo.sqlrisk.extractor.ExtractResult;
import com.demo.sqlrisk.model.ChangeType;
import com.demo.sqlrisk.model.DiffType;
import com.demo.sqlrisk.model.ExtractedStatement;
import com.demo.sqlrisk.util.JavaSourceIndex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T5.4 自测：识别入口、实体解析优先级、语句范围（含执行调用行 DD-7）、条件重载与 incomplete。 */
class LambdaSqlExtractorTest {

    private static final String SERVICE_PATH = "src/main/java/demo/OrderService.java";

    private static final String SERVICE =
            "package demo;\n"                                                              // 1
                    + "\n"
                    + "class OrderService {\n"
                    + "    private OrderMapper orderMapper;\n"
                    + "\n"
                    + "    void inlineChain() {\n"
                    + "        orderMapper.selectList(Wrappers.<Order>lambdaQuery().eq(Order::getUserId, 1L));\n"
                    + "    }\n"
                    + "\n"
                    + "    void variableChain() {\n"
                    + "        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>();\n"
                    + "        w.eq(Order::getStatus, \"NEW\");\n"
                    + "        w.or().likeRight(Order::getOrderNo, \"A\");\n"
                    + "        orderMapper.selectList(w);\n"
                    + "    }\n"
                    + "\n"
                    + "    void diamondAndBoolean() {\n"
                    + "        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<>();\n"
                    + "        w.eq(flag, Order::getUserId, 1L);\n"
                    + "        w.eq(true, Order::getStatus, \"OK\");\n"
                    + "        orderMapper.selectOne(w);\n"
                    + "    }\n"
                    + "\n"
                    + "    void conversion() {\n"
                    + "        orderMapper.selectList(new QueryWrapper<Order>().lambda().eq(Order::getId, 1L));\n"
                    + "    }\n"
                    + "\n"
                    + "    void receiverChain() {\n"
                    + "        orderMapper.lambdaQuery().eq(Order::getStatus, \"X\");\n"
                    + "    }\n"
                    + "\n"
                    + "    void paramWrapper(LambdaQueryWrapper<Order> w) {\n"
                    + "        w.eq(Order::getUserId, 2L);\n"
                    + "    }\n"
                    + "}\n";

    private static final String MAPPER_PATH = "src/main/java/demo/OrderMapper.java";
    private static final String MAPPER =
            "package demo;\npublic interface OrderMapper extends BaseMapper<Order> {}\n";
    private static final String ENTITY_PATH = "src/main/java/demo/Order.java";
    private static final String ENTITY = "package demo;\npublic class Order {}\n";

    private static ExtractContext context() {
        Map<String, String> sources = new LinkedHashMap<String, String>();
        sources.put(SERVICE_PATH, SERVICE);
        sources.put(MAPPER_PATH, MAPPER);
        sources.put(ENTITY_PATH, ENTITY);
        JavaSourceIndex sourceIndex = JavaSourceIndex.build(sources);
        return new ExtractContext(null, EntityIndex.build(sourceIndex), sourceIndex, null);
    }

    private static ExtractResult extractFull() {
        return new LambdaSqlExtractor().extract(CandidateFile.of(SERVICE_PATH, SERVICE), context());
    }

    private static int lineOf(String needle) {
        int index = SERVICE.indexOf(needle);
        assertTrue(index >= 0, "内容中缺少: " + needle);
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (SERVICE.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static LambdaChainModel modelAt(List<ExtractedStatement> statements, int rootLine) {
        for (ExtractedStatement statement : statements) {
            if (statement.line() == rootLine) {
                return (LambdaChainModel) statement.carrier();
            }
        }
        throw new AssertionError("缺少 rootLine=" + rootLine + " 的语句");
    }

    private static List<String> callMethods(LambdaChainModel model) {
        List<String> methods = new ArrayList<String>();
        for (LambdaCall call : model.calls()) {
            methods.add(call.method());
        }
        return methods;
    }

    @Test
    void full_mode_recognizes_all_entry_forms() {
        ExtractResult result = extractFull();
        assertEquals(6, result.statements().size(), "内联/变量/钻石/转换/接收者/参数 六种入口");
        List<ExtractedStatement> statements = result.statements();

        // 内联链：root = lambdaQuery 调用行；range 并入执行调用行
        int inlineLine = lineOf("selectList(Wrappers.<Order>lambdaQuery()");
        LambdaChainModel inline = modelAt(statements, inlineLine);
        assertEquals("Order", inline.entityTypeName(), "显式泛型");
        assertEquals(Collections.singletonList("eq"), callMethods(inline));
        assertEquals(inlineLine, inline.rangeEndLine());

        // 变量链：root = 构造行；后续对 w 的调用按源码顺序归并；range 计入执行调用行（DD-7）
        int variableLine = lineOf("new LambdaQueryWrapper<Order>();");
        LambdaChainModel variable = modelAt(statements, variableLine);
        assertEquals(java.util.Arrays.asList("eq", "or", "likeRight"), callMethods(variable));
        assertEquals(lineOf("orderMapper.selectList(w);"), variable.rangeEndLine(), "执行调用行纳入语句范围");
        assertEquals("Order", variable.entityTypeName());

        // 钻石泛型 + 布尔条件重载：运行期条件（flag）→ runtimeConditional；字面量 true → 纳入其余参数
        int diamondLine = lineOf("new LambdaQueryWrapper<>();");
        LambdaChainModel diamond = modelAt(statements, diamondLine);
        assertEquals("Order", diamond.entityTypeName(), "实体来自变量声明类型");
        assertEquals(2, diamond.calls().size());
        assertTrue(diamond.calls().get(0).runtimeConditional(), "变量条件（flag）按保守策略丢弃");
        assertFalse(diamond.calls().get(1).runtimeConditional(), "字面量 true → 纳入");
        assertEquals(LambdaArg.Kind.S_FUNCTION, diamond.calls().get(1).args().get(0).kind());
        assertEquals("getStatus", diamond.calls().get(1).args().get(0).text());
        assertEquals(lineOf("orderMapper.selectOne(w);"), diamond.rangeEndLine());

        // QueryWrapper → lambda() 转换
        int conversionLine = lineOf(".lambda().eq(Order::getId");
        LambdaChainModel conversion = modelAt(statements, conversionLine);
        assertEquals("Order", conversion.entityTypeName(), "泛型来自转换的接收者构造");
        assertEquals(Collections.singletonList("eq"), callMethods(conversion));

        // 接收者（mapper 泛型反解）
        int receiverLine = lineOf("orderMapper.lambdaQuery()");
        LambdaChainModel receiver = modelAt(statements, receiverLine);
        assertEquals("Order", receiver.entityTypeName(), "BaseMapper<T> 泛型反解");

        // 方法参数型 wrapper：还原不完整（D11）
        int paramLine = lineOf("w.eq(Order::getUserId, 2L);");
        LambdaChainModel param = modelAt(statements, paramLine);
        assertTrue(param.incomplete(), "wrapper 由参数传入 → incomplete");
        assertEquals("Order", param.entityTypeName());
        assertEquals(Collections.singletonList("eq"), callMethods(param));

        // statementId 规则：文件名(不含路径):行号（不参与白名单）
        assertEquals("OrderService.java:" + variableLine,
                statements.get(indexOfLine(statements, variableLine)).statementId());
    }

    @Test
    void diff_mode_uses_chain_range_including_execution_call_line() {
        LambdaSqlExtractor extractor = new LambdaSqlExtractor();

        // 变更行 = 链式调用行（w.or().likeRight）→ 仅该语句 MODIFIED
        ExtractResult modified = extractor.extract(
                CandidateFile.of(SERVICE_PATH, SERVICE, DiffType.MODIFY, modify(lineOf("w.or().likeRight"))),
                context());
        assertEquals(1, modified.statements().size());
        assertEquals(ChangeType.MODIFIED, modified.statements().get(0).changeType());
        assertEquals(lineOf("new LambdaQueryWrapper<Order>();"), modified.statements().get(0).line());

        // 变更行 = 执行调用行（orderMapper.selectList(w)）→ 仍在语句范围内（DD-7）
        ExtractResult viaExecution = extractor.extract(
                CandidateFile.of(SERVICE_PATH, SERVICE, DiffType.MODIFY, modify(lineOf("orderMapper.selectList(w);"))),
                context());
        assertEquals(1, viaExecution.statements().size());
        assertEquals(ChangeType.MODIFIED, viaExecution.statements().get(0).changeType());

        // 变更行 = 无关行（package 行）→ 无提取
        ExtractResult none = extractor.extract(
                CandidateFile.of(SERVICE_PATH, SERVICE, DiffType.MODIFY, modify(1)),
                context());
        assertTrue(none.statements().isEmpty());
    }

    private static FileChange modify(int line) {
        TreeSet<Integer> lines = new TreeSet<Integer>(Collections.singleton(line));
        return new FileChange(SERVICE_PATH, DiffType.MODIFY,
                Collections.singletonList(new Hunk(0, 0, line, 1, lines, new TreeSet<Integer>(lines))));
    }

    private static int indexOfLine(List<ExtractedStatement> statements, int line) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).line() == line) {
                return i;
            }
        }
        throw new AssertionError("缺少行 " + line);
    }
}
