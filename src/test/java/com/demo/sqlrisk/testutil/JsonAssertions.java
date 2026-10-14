package com.demo.sqlrisk.testutil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;

/**
 * JSON 深度等价断言（详细设计 §18.2）：忽略对象键顺序与空白差异，失败时输出两侧全量美化文本。
 */
public final class JsonAssertions {

    private static final int MAX_PRINT = 4000;

    private JsonAssertions() {
    }

    public static void assertEqualsJson(String context, String expectedJson, String actualJson) {
        JsonElement expected = JsonParser.parseString(expectedJson);
        JsonElement actual = JsonParser.parseString(actualJson);
        if (!expected.equals(actual)) {
            Gson pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            String message = context + " 不一致：\n--- expected ---\n" + truncate(pretty.toJson(expected))
                    + "\n--- actual ---\n" + truncate(pretty.toJson(actual));
            Assertions.fail(message);
        }
    }

    private static String truncate(String s) {
        return s.length() <= MAX_PRINT ? s : s.substring(0, MAX_PRINT) + "\n…(截断)";
    }
}
