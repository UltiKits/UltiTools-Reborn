package com.ultikits.ultitools.webserver.controller;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.webserver.service.ConfigEditorService;
import com.ultikits.ultitools.webserver.service.impl.ConfigEditorServiceImpl;
import com.ultikits.ultitools.webserver.wrapper.ResultWrapper;

import static spark.Spark.*;

public class ConfigEditorController {
    private ConfigEditorService configEditorService = new ConfigEditorServiceImpl();

    public void init() {
        path("/config", () -> {
            before("/*", (request, response) -> {
                if (!request.ip().equals("47.242.179.141") &&
                        !UltiTools.getInstance().getConfig().getStringList("web-editor.trustIp")
                                .contains(request.ip())) {
                    halt("Access Denied!");
                }
            });
            post("/update", (request, response) -> {
                String body = request.body();
                configEditorService.updateConfigMap(body);
                return ResultWrapper.success();
            });
            get("/get", (request, response) ->
                    configEditorService.getConfigMapString()
            );
            get("/comment", (request, response) ->
                    configEditorService.getCommentMapString()
            );
            after("/*", (request, response) -> {
                response.type("text/json; charset=utf-8");
            });
        });
    }
}
