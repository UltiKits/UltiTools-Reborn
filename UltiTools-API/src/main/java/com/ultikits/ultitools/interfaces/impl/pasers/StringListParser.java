package com.ultikits.ultitools.interfaces.impl.pasers;

import java.util.ArrayList;
import java.util.List;

public class StringListParser extends BaseParser<List<String>> {

    @Override
    public List<String> parse(Object object) {
        List<String> list = new ArrayList<>();
        for (Object o : (List<?>) object) {
            list.add(o.toString());
        }
        return list;
    }
}
