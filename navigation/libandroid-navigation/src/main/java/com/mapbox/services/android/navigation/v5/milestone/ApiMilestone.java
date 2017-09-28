package com.mapbox.services.android.navigation.v5.milestone;

import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress;
import com.mapbox.services.api.directions.v5.models.Voice;

public class ApiMilestone extends Milestone {

  public ApiMilestone(Builder builder) {
    super(builder);
  }

  @Override
  public boolean isOccurring(RouteProgress previousRouteProgress, RouteProgress routeProgress) {
    for (Voice voice : routeProgress.currentLegProgress().currentStep().getVoice()) {
      if (voice.getDistanceAlongGeometry()
        <= routeProgress.currentLegProgress().currentStepProgress().distanceRemaining()) {
        routeProgress.currentLegProgress().currentStep().getVoice().remove(voice);
        return true;
      }
    }
    return false;
  }

  public static final class Builder extends Milestone.Builder {

    private Trigger.Statement trigger;

    public Builder() {
      super();
    }

    @Override
    public Builder setTrigger(Trigger.Statement trigger) {
      this.trigger = trigger;
      return this;
    }

    @Override
    Trigger.Statement getTrigger() {
      return trigger;
    }

    @Override
    public ApiMilestone build() {
      return new ApiMilestone(this);
    }
  }
}
