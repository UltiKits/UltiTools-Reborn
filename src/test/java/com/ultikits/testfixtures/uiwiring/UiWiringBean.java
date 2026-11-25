package com.ultikits.testfixtures.uiwiring;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.ui.UiService;

@Service
public class UiWiringBean {
    @Autowired public UiService ui;
    public String ownerAtConstruction;

    @PostConstruct
    public void recordOwner() { ownerAtConstruction = ui.owner(); }
}
