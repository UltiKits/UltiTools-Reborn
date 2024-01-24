package com.ultikits.ultitools.webserver.service;

import java.io.IOException;

public interface ConfigEditorService {


    String getConfigMapString();

    String getCommentMapString();

    void updateConfigMap(String configMapString) throws IOException;
}
