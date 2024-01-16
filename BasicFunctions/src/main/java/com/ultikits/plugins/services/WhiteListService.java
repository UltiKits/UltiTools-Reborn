package com.ultikits.plugins.services;

import com.ultikits.plugins.BasicFunctions;
import com.ultikits.plugins.data.WhiteListData;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WhiteListService {

    public boolean isWhiteList(String uuid) {
        DataOperator<WhiteListData> dataOperator = BasicFunctions.getInstance().getDataOperator(WhiteListData.class);
        return dataOperator.exist(WhereCondition.builder().column("id").value(uuid).build());
    }

    public List<WhiteListData> getAllWhiteList() {
        DataOperator<WhiteListData> dataOperator = BasicFunctions.getInstance().getDataOperator(WhiteListData.class);
        return dataOperator.getAll();
    }

    public void addWhiteList(String uuid, String name) {
        DataOperator<WhiteListData> dataOperator = BasicFunctions.getInstance().getDataOperator(WhiteListData.class);
        if (dataOperator.getById(uuid) != null) {
            return;
        }
        WhiteListData whiteListData = new WhiteListData();
        whiteListData.setId(uuid);
        whiteListData.setName(name);
        dataOperator.insert(whiteListData);
    }

    public void removeWhiteList(String uuid) {
        DataOperator<WhiteListData> dataOperator = BasicFunctions.getInstance().getDataOperator(WhiteListData.class);
        if (dataOperator.getById(uuid) == null) {
            return;
        }
        dataOperator.delById(uuid);
    }
}
