package com.demo.sqlrisk.metadata;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 表元数据 JSON 实现（详细设计 §7、需求 §9.1）：
 * 文件缺失（含默认路径）/JSON 语法错误/结构校验失败 → {@link MetadataException}（exit 2）；
 * 重复表名取首条并告警；表名与列名匹配统一大小写不敏感。
 */
public final class JsonMetadataProvider implements MetadataProvider {

    private final Map<String, TableMetadata> byLowerTableName;

    private JsonMetadataProvider(Map<String, TableMetadata> byLowerTableName) {
        this.byLowerTableName = byLowerTableName;
    }

    public static JsonMetadataProvider load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new MetadataException("元数据文件不存在: " + file);
        }
        MetaFileDto dto;
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            dto = new Gson().fromJson(reader, MetaFileDto.class);
        } catch (IOException e) {
            throw new MetadataException("元数据文件读取失败: " + file + "（" + e.getMessage() + "）");
        } catch (JsonParseException e) {
            throw new MetadataException("元数据 JSON 解析失败: " + file + "（" + e.getMessage() + "）");
        }
        return fromDto(dto, file);
    }

    @Override
    public Optional<TableMetadata> find(String tableName) {
        if (tableName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byLowerTableName.get(tableName.toLowerCase(Locale.ROOT)));
    }

    // ---------- 校验与构建 ----------

    private static JsonMetadataProvider fromDto(MetaFileDto dto, Path file) {
        if (dto == null || dto.tables == null) {
            throw new MetadataException("元数据缺少 tables 数组: " + file);
        }
        Map<String, TableMetadata> byLower = new LinkedHashMap<String, TableMetadata>();
        for (TableDto tableDto : dto.tables) {
            if (tableDto == null || isBlank(tableDto.table)) {
                throw new MetadataException("元数据校验失败：tables[].table 不能为空（" + file + "）");
            }
            String tableName = tableDto.table.trim();
            String key = tableName.toLowerCase(Locale.ROOT);
            if (byLower.containsKey(key)) {
                System.err.println("[警告] 元数据中重复表名，取首条: " + tableName);
                continue;
            }
            byLower.put(key, new TableMetadata(tableName, parseFields(tableDto, tableName), parseIndexes(tableDto, tableName)));
        }
        return new JsonMetadataProvider(Collections.unmodifiableMap(byLower));
    }

    private static Map<String, FieldMeta> parseFields(TableDto tableDto, String tableName) {
        Map<String, FieldMeta> fields = new LinkedHashMap<String, FieldMeta>();
        if (tableDto.fields == null) {
            return fields;
        }
        for (FieldDto fieldDto : tableDto.fields) {
            if (fieldDto == null || isBlank(fieldDto.name) || isBlank(fieldDto.type)) {
                throw new MetadataException("元数据校验失败：表 " + tableName + " 的 fields[].name/type 不能为空");
            }
            FieldMeta field = new FieldMeta(fieldDto.name.trim(), fieldDto.type.trim());
            fields.put(field.name().toLowerCase(Locale.ROOT), field);
        }
        return fields;
    }

    private static List<IndexMeta> parseIndexes(TableDto tableDto, String tableName) {
        List<IndexMeta> indexes = new ArrayList<IndexMeta>();
        if (tableDto.indexes == null) {
            return indexes;
        }
        for (IndexDto indexDto : tableDto.indexes) {
            if (indexDto == null || indexDto.columns == null || indexDto.columns.isEmpty()) {
                throw new MetadataException("元数据校验失败：表 " + tableName + " 的 indexes[].columns 不能为空");
            }
            List<String> columns = new ArrayList<String>();
            for (String column : indexDto.columns) {
                if (isBlank(column)) {
                    throw new MetadataException("元数据校验失败：表 " + tableName + " 的索引列名不能为空");
                }
                columns.add(column.trim());
            }
            indexes.add(new IndexMeta(indexDto.name == null ? "" : indexDto.name.trim(), columns));
        }
        return indexes;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ---------- JSON DTO ----------

    private static final class MetaFileDto {
        String version;
        List<TableDto> tables;
    }

    private static final class TableDto {
        String table;
        List<FieldDto> fields;
        List<IndexDto> indexes;
    }

    private static final class FieldDto {
        String name;
        String type;
    }

    private static final class IndexDto {
        String name;
        List<String> columns;
    }
}
