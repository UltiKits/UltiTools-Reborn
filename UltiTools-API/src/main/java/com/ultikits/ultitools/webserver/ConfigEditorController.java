package com.ultikits.ultitools.webserver;

import com.ultikits.ultitools.webserver.service.ConfigEditorService;
import com.ultikits.ultitools.webserver.service.impl.ConfigEditorServiceImpl;
import com.ultikits.ultitools.webserver.wrapper.ResultWrapper;

import static spark.Spark.*;

public class ConfigEditorController {
    private ConfigEditorService configEditorService = new ConfigEditorServiceImpl();

    public void init() {
        path("/config", () -> {
            before("/*", (request, response) -> {
                System.out.println(request.ip());
                if (!request.ip().equals("47.242.179.141")){
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
                response.type("text/json");
            });
        });
    }
}
