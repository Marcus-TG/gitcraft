package com.gitcraft.util;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Centralizes Sponge .schem reading. Always called from async — does blocking file IO.
 */
public final class ClipboardLoader {

    private ClipboardLoader() {}

    public static Clipboard load(String schemPath) throws IOException {
        return load(Paths.get(schemPath));
    }

    public static Clipboard load(Path schemPath) throws IOException {
        File file = schemPath.toFile();
        if (!file.exists()) {
            throw new IOException("Schematic file missing: " + schemPath);
        }
        ClipboardFormat fmt = ClipboardFormats.findByFile(file);
        if (fmt == null) {
            throw new IOException("Unrecognized schematic format: " + schemPath);
        }
        try (InputStream in = Files.newInputStream(file.toPath());
             ClipboardReader reader = fmt.getReader(in)) {
            return reader.read();
        }
    }
}
