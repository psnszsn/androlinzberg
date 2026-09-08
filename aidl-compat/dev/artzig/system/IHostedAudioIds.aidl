package dev.artzig.system;

/** Session-wide audio ID allocation normally supplied by AudioFlinger. */
interface IHostedAudioIds {
    int nextId(int use);
}
