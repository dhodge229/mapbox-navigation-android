package com.mapbox.services.android.navigation.ui.v5;

import com.mapbox.directions.v5.models.DirectionsRoute;
import com.mapbox.geojson.Point;

public interface NavigationContract {

  interface View {

    void setSummaryBehaviorState(int state);

    void setSummaryBehaviorHideable(boolean isHideable);

    void setSummaryOptionsVisibility(boolean isVisible);

    void setSummaryDirectionsVisibility(boolean isVisible);

    boolean isSummaryDirectionsVisible();

    void setSheetShadowVisibility(boolean isVisible);

    void setCameraTrackingEnabled(boolean isEnabled);

    void resetCameraPosition();

    void showRecenterBtn();

    void hideRecenterBtn();

    void showInstructionView();

    void drawRoute(DirectionsRoute directionsRoute);

    void addMarker(Point point);

    void finishNavigationView();

    void setMuted(boolean isMuted);

    void setCancelBtnClickable(boolean isClickable);

    void animateCancelBtnAlpha(float value);

    void animateExpandArrowRotation(float value);

    void animateInstructionViewAlpha(float value);
  }
}
