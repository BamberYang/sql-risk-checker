package com.demo.sqlrisk.extractor.annotation;

import com.demo.sqlrisk.util.JavaSourceIndex;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** T5.3 自测：Provider 方法静态求值支持的语法子集与失败语义。 */
class ProviderMethodResolverTest {

    private static final String PROVIDER =
            "package com.demo.provider;\n"
                    + "public class SqlProvider {\n"
                    + "    public String literal() { return \"SELECT 1\"; }\n"
                    + "    public String concat() { return \"SELECT \" + \"2\"; }\n"
                    + "    public String builder() {\n"
                    + "        StringBuilder sb = new StringBuilder();\n"
                    + "        sb.append(\"SELECT \");\n"
                    + "        sb.append(\"3\");\n"
                    + "        return sb.toString();\n"
                    + "    }\n"
                    + "    public String chained() { return new StringBuilder().append(\"SELECT \").append(\"4\").toString(); }\n"
                    + "    public String unsupported(String arg) { return decide(arg); }\n"
                    + "}\n";

    private static ProviderMethodResolver resolver() {
        Map<String, String> sources = new LinkedHashMap<String, String>();
        sources.put("src/main/java/com/demo/provider/SqlProvider.java", PROVIDER);
        return new ProviderMethodResolver(JavaSourceIndex.build(sources));
    }

    private static Optional<String> resolve(String method) {
        return resolver().resolve("com.demo.provider.SqlProvider", method,
                StaticJavaParser.parse("package demo; class C {}"));
    }

    @Test
    void supported_syntax_subset() {
        assertEquals(Optional.of("SELECT 1"), resolve("literal"));
        assertEquals(Optional.of("SELECT 2"), resolve("concat"), "字面量拼接");
        assertEquals(Optional.of("SELECT 3"), resolve("builder"), "StringBuilder append 序列");
        assertEquals(Optional.of("SELECT 4"), resolve("chained"), "链式 builder 表达式");
    }

    @Test
    void unsupported_or_missing_yields_empty() {
        assertFalse(resolve("unsupported").isPresent(), "非常量表达式：不猜测");
        assertFalse(resolve("notExists").isPresent());
        assertFalse(resolver().resolve("demo.NoSuchType", "literal",
                StaticJavaParser.parse("package demo; class C {}")).isPresent());
    }
}
