package com.ultikits.ultitools.entities;

import com.ultikits.ultitools.proxy.InventoryProxy;

import java.util.LinkedList;

public class GUISession {
    private LinkedList<InventoryProxy> inventoryProxyLinkedList = new LinkedList<>();

    public void addView(InventoryProxy inventoryProxy){
        this.inventoryProxyLinkedList.add(inventoryProxy);
    }

    public InventoryProxy getLastView(){
        return this.inventoryProxyLinkedList.getLast();
    }


}
