package com.ultikits.ultitools.abstracts;

import cn.hutool.core.io.FileUtil;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.IPlugin;
import com.ultikits.ultitools.interfaces.Localized;
import lombok.SneakyThrows;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public abstract class UltiToolsPlugin implements IPlugin, Localized {
    private static final DataStore dataStore = UltiTools.getInstance().getDataStore();
    private final Language language;

    public UltiToolsPlugin() {
        String lanPath = "lang/" + this.getLanguageCode() + ".json";
        InputStream in = getResource(lanPath);
        String result = new BufferedReader(new InputStreamReader(in))
                .lines().collect(Collectors.joining(""));
        language = new Language(result);
        List<String> fileList = getFileList();
        for (String fileName : fileList) {
            saveResource(UltiTools.getInstance().getDataFolder().getAbsolutePath() + "/pluginConfig/" + this.pluginName(), fileName, fileName);
        }
    }

    @SneakyThrows
    private List<String> getFileList() {
        List<String> filePaths = new ArrayList<>();
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        if (src != null) {
            URL jar = src.getLocation();
            ZipInputStream zip = new ZipInputStream(jar.openStream());
            while (true) {
                ZipEntry e = zip.getNextEntry();
                if (e == null)
                    break;
                String name = e.getName();
                if (name.startsWith("res")) {
                    filePaths.add(name);
                }
            }
            zip.close();
        }
        return filePaths;
    }

    private void saveResource(String filePath, String resourcePath, String outFileName) {
        if (!outFileName.contains(".")) {
            return;
        }
        if (resourcePath.equals("")) {
            throw new IllegalArgumentException("ResourcePath cannot be null or empty");
        }

        resourcePath = resourcePath.replace('\\', '/');
        InputStream in = getResource(resourcePath);
        if (in == null) {
            throw new IllegalArgumentException("The embedded resource '" + resourcePath + "' cannot be found in " + resourcePath);
        }

        File outFile = new File(filePath, outFileName);

        try {
            if (!outFile.exists()) {
                FileUtil.touch(outFile);
                OutputStream out = Files.newOutputStream(outFile.toPath());
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.close();
                in.close();
            }
        } catch (IOException ex) {
            System.out.println("Could not save " + outFile.getName() + " to " + outFile);
        }
    }

    private InputStream getResource(String filename) {
        try {
            URL url = this.getClass().getClassLoader().getResource(filename);

            if (url == null) {
                return null;
            }

            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            return connection.getInputStream();
        } catch (IOException ex) {
            return null;
        }
    }

    public <T extends DataEntity> DataOperator<T> getDataOperator(Class<T> dataClazz) {
        return dataStore.getOperator(this, dataClazz);
    }

    public String getLanguageCode() {
        return UltiTools.getInstance().getConfig().getString("language");
    }

    public Language getLanguage() {
        return language;
    }

    public String i18n(String str) {
        return this.i18n(UltiTools.getInstance().getConfig().getString("language"), str);
    }

    @Override
    public final String i18n(String code, String str) {
        return this.getLanguage().getLocalizedText(str);
    }

}
