package com.ultikits.ultitools.bootstrap;

@FunctionalInterface
interface TagWriter {
    void apply(ModuleContributions.TagChange change);
}
