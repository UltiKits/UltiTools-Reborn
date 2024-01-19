package com.ultikits.plugins.basic.services;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.data.WhiteListData;
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
        addWhiteList(uuid, name, "");
    }

    public void addWhiteList(String uuid, String name, String remark) {
        DataOperator<WhiteListData> dataOperator = BasicFunctions.getInstance().getDataOperator(WhiteListData.class);
        if (dataOperator.getById(uuid) != null) {
            return;
        }
        WhiteListData whiteListData = new WhiteListData();
        whiteListData.setId(uuid);
        whiteListData.setName(name);
        whiteListData.setRemark(remark);
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
