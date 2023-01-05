package com.ultikits.ultitools.webserver.service;

public interface ConfigEditorService {


    String getConfigMapString();

    String getCommentMapString();

    void updateConfigMap(String configMapString);
}
