package dev.netro.model;

/** World and block coordinates for chunk loading (e.g. detector rail or station sign). */
public record BlockPos(String world, int x, int z) {}
