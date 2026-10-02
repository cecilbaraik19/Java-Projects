package com.cecil.cloudmonitor.dto;

import com.cecil.cloudmonitor.model.CloudResource;

import java.util.List;

/** What the server pushes to every dashboard every tick. */
public record LiveUpdate(OverviewDto overview, List<CloudResource> resources) {
}
