package com.demo.sqlrisk.diff;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提交树内容读取（详细设计 §8.2）：仅从指定提交的树读取，工作区文件永不参与（D21/D22）。
 *
 * <p>编码：XML 按文件头 {@code <?xml encoding=...?>} 声明探测（缺省 UTF-8）；其余文本按 UTF-8。
 * 方法同步（按需构建 path→blob 索引）：M5 并行提取阶段多线程共享本实例（§16）。</p>
 */
public final class RepoContentReader {

    private static final Pattern XML_ENCODING =
            Pattern.compile("encoding\\s*=\\s*[\"']([A-Za-z0-9_\\-]+)[\"']");

    private final Repository repository;
    private final RevCommit commit;
    private Map<String, ObjectId> blobs; // lazy：path → blob id

    RepoContentReader(Repository repository, RevCommit commit) {
        this.repository = repository;
        this.commit = commit;
    }

    /** 读取提交树内文件原始字节；文件不在该提交中 → empty。 */
    public synchronized Optional<byte[]> read(String path) {
        ObjectId id = blobsOrLoad().get(path);
        if (id == null) {
            return Optional.empty();
        }
        try {
            ObjectLoader loader = repository.open(id);
            return Optional.of(loader.getBytes());
        } catch (IOException e) {
            throw new DiffException("读取提交内容失败: " + path + "（" + e.getMessage() + "）", e);
        }
    }

    /** 读取并解码文本（XML 探测声明编码，其余 UTF-8）。 */
    public synchronized Optional<String> readText(String path) {
        Optional<byte[]> bytes = read(path);
        if (!bytes.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(decode(path, bytes.get()));
    }

    /** 提交树内匹配过滤器的全部路径（字典序；来源为提交树而非工作区）。 */
    public synchronized List<String> listFiles(Predicate<String> filter) {
        List<String> out = new ArrayList<String>();
        for (String path : new TreeMap<String, ObjectId>(blobsOrLoad()).keySet()) {
            if (filter.test(path)) {
                out.add(path);
            }
        }
        return out;
    }

    private Map<String, ObjectId> blobsOrLoad() {
        if (blobs == null) {
            Map<String, ObjectId> map = new LinkedHashMap<String, ObjectId>();
            try (TreeWalk walk = new TreeWalk(repository)) {
                walk.addTree(commit.getTree());
                walk.setRecursive(true);
                while (walk.next()) {
                    map.put(walk.getPathString(), walk.getObjectId(0));
                }
            } catch (IOException e) {
                throw new DiffException("遍历提交树失败（" + commit.getName() + "）：" + e.getMessage(), e);
            }
            blobs = map;
        }
        return blobs;
    }

    private static String decode(String path, byte[] bytes) {
        Charset charset = StandardCharsets.UTF_8;
        if (path.toLowerCase(Locale.ROOT).endsWith(".xml")) {
            charset = detectXmlCharset(bytes);
        }
        return new String(bytes, charset);
    }

    /** 声明片段为 ASCII 兼容：用 ISO-8859-1 解码文件头后匹配 encoding=... */
    private static Charset detectXmlCharset(byte[] bytes) {
        int length = Math.min(bytes.length, 512);
        String head = new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
        Matcher matcher = XML_ENCODING.matcher(head);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (RuntimeException ignored) {
                // 未知编码声明 → 回退 UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }
}
