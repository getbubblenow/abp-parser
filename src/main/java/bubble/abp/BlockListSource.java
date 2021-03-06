package bubble.abp;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import static org.cobbzilla.util.daemon.ZillaRuntime.*;
import static org.cobbzilla.util.http.HttpUtil.getUrlInputStream;
import static org.cobbzilla.util.io.FileUtil.basename;

@NoArgsConstructor @Accessors(chain=true) @Slf4j
public class BlockListSource {

    public static final String INCLUDE_PREFIX = "!#include ";
    public static final String TITLE_PREFIX = "!Title:";
    public static final String DESCRIPTION_PREFIX = "!Description:";
    public static final String WHITELIST_PREFIX = "@@";
    public static final String REJECT_LIST_PREFIX = "~~";

    @Getter @Setter private String url;

    @Getter @Setter private String title;
    public boolean hasTitle () { return !empty(title); }

    @Getter @Setter private String description;
    public boolean hasDescription () { return !empty(description); }

    @Getter @Setter private String format;

    @Getter @Setter private Long lastDownloaded;
    public long age () { return lastDownloaded == null ? Long.MAX_VALUE : now() - lastDownloaded; }

    @Getter @Setter private BlockList blockList = new BlockList();

    public InputStream urlInputStream() throws IOException { return getUrlInputStream(url); }

    public BlockListSource download() throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(urlInputStream()))) {
            String line;
            boolean firstLine = true;
            int lineNumber = 1;
            while ( (line = r.readLine()) != null ) {
                if (empty(line)) {
                    lineNumber++;
                    continue;
                }
                line = line.trim();
                if (firstLine && line.startsWith("[") && line.endsWith("]")) {
                    format = line.substring(1, line.length()-1);
                }
                firstLine = false;
                addLine(url, lineNumber, line);
                lineNumber++;
            }
        }
        lastDownloaded = now();
        return this;
    }

    private void addLine(String url, int lineNumber, String line) throws IOException {
        if (line.startsWith(INCLUDE_PREFIX) && !empty(url)) {
            final String includePath = line.substring(INCLUDE_PREFIX.length()).trim();
            final String base = basename(url);
            final String urlPrefix = url.substring(0, url.length() - base.length());
            final String includeUrl = urlPrefix + includePath;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(getUrlInputStream(includeUrl)))) {
                String includeLine;
                int includeLineNumber = 1;
                while ((includeLine = r.readLine()) != null) {
                    addLine(includeUrl, includeLineNumber, includeLine);
                    includeLineNumber++;
                }
            } catch (Exception e) {
                throw new IOException("addLine: error including path: " + includeUrl + ": " + shortError(e));
            }
        } else if (lineNumber < 20 && empty(title) && line.replace(" ", "").startsWith(TITLE_PREFIX)) {
            title = getMetadata(line);

        } else if (lineNumber < 20 && empty(description) && line.replace(" ", "").startsWith(DESCRIPTION_PREFIX)) {
            description = getMetadata(line);

        } else if (line.startsWith("!")) {
            // comment, nothing to add
            return;
        }
        try {
            if (line.startsWith(WHITELIST_PREFIX)) {
                blockList.addToWhitelist(BlockSpec.parse(line.substring(WHITELIST_PREFIX.length())));

            } else if (line.startsWith(REJECT_LIST_PREFIX)) {
                line = line.substring(REJECT_LIST_PREFIX.length());
                blockList.addToBlacklist(BlockSpec.parse(line));
                blockList.addToRejectList(line.trim());

            } else {
                blockList.addToBlacklist(BlockSpec.parse(line));
            }
        } catch (Exception e) {
            log.warn("download("+url+"): error parsing line (skipping due to "+shortError(e)+"): " + line);
        }
    }

    private String getMetadata(String line) { return line.substring(line.indexOf(":")+1).trim(); }

    public void addEntries(String[] entries) throws IOException { for (String entry : entries) addLine(null, 1, entry); }

}
