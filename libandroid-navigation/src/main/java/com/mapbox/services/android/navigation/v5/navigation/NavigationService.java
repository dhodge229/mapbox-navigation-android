package com.mapbox.services.android.navigation.v5.navigation;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;

import com.mapbox.services.android.location.MockLocationEngine;
import com.mapbox.services.android.navigation.R;
import com.mapbox.services.android.navigation.v5.milestone.Milestone;
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress;
import com.mapbox.services.android.navigation.v5.utils.RingBuffer;
import com.mapbox.services.android.telemetry.location.LocationEngine;
import com.mapbox.services.android.telemetry.location.LocationEngineListener;
import com.mapbox.services.api.directions.v5.models.DirectionsRoute;
import com.mapbox.services.api.utils.turf.TurfConstants;
import com.mapbox.services.api.utils.turf.TurfMeasurement;
import com.mapbox.services.commons.models.Position;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

import static com.mapbox.services.android.navigation.v5.navigation.NavigationConstants.NAVIGATION_NOTIFICATION_ID;
import static com.mapbox.services.android.navigation.v5.navigation.NavigationHelper.buildInstructionString;

/**
 * Internal usage only, use navigation by initializing a new instance of {@link MapboxNavigation}
 * and customizing the navigation experience through that class.
 * <p>
 * This class is first created and started when {@link MapboxNavigation#startNavigation(DirectionsRoute)}
 * get's called and runs in the background until either the navigation sessions ends implicitly or
 * the hosting activity gets destroyed. Location updates are also tracked and handled inside this
 * service. Thread creation gets created in this service and maintains the thread until the service
 * gets destroyed.
 * </p>
 */
public class NavigationService extends Service implements LocationEngineListener,
  NavigationEngine.Callback {

  // Message id used when a new location update occurs and we send to the thread.
  private static final int MSG_LOCATION_UPDATED = 1001;
  private static final int TWENTY_SECOND_INTERVAL = 20000;

  private RingBuffer<Integer> recentDistancesFromManeuverInMeters;
  private final IBinder localBinder = new LocalBinder();
  private NavigationNotification navNotificationManager;
  private RingBuffer<Location> locationBuffer;
  private long timeIntervalSinceLastOffRoute;
  private MapboxNavigation mapboxNavigation;
  private LocationEngine locationEngine;
  private RouteProgress routeProgress;
  private boolean firstProgressUpdate = true;
  private boolean queuedRerouteEvent;
  private NavigationEngine thread;
  private Location rawLocation;
  private Runnable runnable;
  private Handler handler;

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return localBinder;
  }

  @Override
  public void onCreate() {
    thread = new NavigationEngine(new Handler(), this);
    thread.start();
    thread.prepareHandler();
    recentDistancesFromManeuverInMeters = new RingBuffer<>(3);
    locationBuffer = new RingBuffer<>(20);
  }

  /**
   * Only should be called once since we want the service to continue running until the navigation
   * session ends.
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    if (mapboxNavigation.options().enableNotification()) {
      stopForeground(true);
    }

    if (handler != null && runnable != null) {
      if (queuedRerouteEvent) {
        runnable.run();
      }
      handler.removeCallbacks(runnable);

    }

    // User canceled navigation session
    if (routeProgress != null && rawLocation != null) {
      NavigationMetricsWrapper.cancelEvent(mapboxNavigation.getSessionState(), routeProgress,
        rawLocation);
    }
    endNavigation();
    super.onDestroy();
  }

  /**
   * This gets called when {@link MapboxNavigation#startNavigation(DirectionsRoute)} is called and
   * setups variables among other things on the Navigation Service side.
   */
  void startNavigation(MapboxNavigation mapboxNavigation) {
    this.mapboxNavigation = mapboxNavigation;
    if (mapboxNavigation.options().enableNotification()) {
      initializeNotification();
    }
    acquireLocationEngine();
    forceLocationUpdate();
  }

  /**
   * builds a new navigation notification instance and attaches it to this service.
   */
  private void initializeNotification() {
    navNotificationManager = new NavigationNotification(this, mapboxNavigation);
    Notification notifyBuilder
      = navNotificationManager.buildPersistentNotification(R.layout.layout_notification_default,
      R.layout.layout_notification_default_big);

    notifyBuilder.flags = Notification.FLAG_FOREGROUND_SERVICE;
    startForeground(NAVIGATION_NOTIFICATION_ID, notifyBuilder);
  }

  /**
   * Specifically removes this locationEngine listener which was added at the very beginning, quits
   * the thread, and finally stops this service from running in the background.
   */
  void endNavigation() {
    locationEngine.removeLocationEngineListener(this);
    if (navNotificationManager != null) {
      navNotificationManager.unregisterReceiver();
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
      thread.quitSafely();
    } else {
      thread.quit();
    }
  }

  /**
   * Location engine already checks if the listener isn't already added so no need to check here.
   * If the user decides to call {@link MapboxNavigation#setLocationEngine(LocationEngine)} during
   * the navigation session, this gets called again in order to attach the location listener to the
   * new engine.
   */
  void acquireLocationEngine() {
    locationEngine = mapboxNavigation.getLocationEngine();
    locationEngine.addLocationEngineListener(this);
  }

  /**
   * At the very beginning of navigation session, a forced location update occurs so that the
   * developer can immediately get a routeProgress object to display information.
   */
  @SuppressWarnings("MissingPermission")
  private void forceLocationUpdate() {
    Location lastLocation = locationEngine.getLastLocation();
    if (lastLocation != null) {
      rawLocation = lastLocation;
      thread.queueTask(MSG_LOCATION_UPDATED, NewLocationModel.create(lastLocation, mapboxNavigation,
        recentDistancesFromManeuverInMeters));
    }
  }

  @Override
  @SuppressWarnings("MissingPermission")
  public void onConnected() {
    Timber.d("NavigationService now connected to rawLocation listener.");
    locationEngine.requestLocationUpdates();
  }

  @Override
  public void onLocationChanged(Location location) {
    Timber.d("onLocationChanged");
    if (location != null && validLocationUpdate(location)) {
      rawLocation = location;
      locationBuffer.push(location);
      thread.queueTask(MSG_LOCATION_UPDATED, NewLocationModel.create(location, mapboxNavigation,
        recentDistancesFromManeuverInMeters));
    }
  }

  /**
   * Runs several checks on the actual rawLocation object itself in order to ensure that we are
   * performing navigation progress on a accurate/valid rawLocation update.
   */
  @SuppressWarnings("MissingPermission")
  private boolean validLocationUpdate(Location location) {
    // TODO fix mock rawLocation engine and remove this if statement.
    if (locationEngine instanceof MockLocationEngine) {
      return true;
    }
    if (locationEngine.getLastLocation() == null) {
      return true;
    }
    // If the locations the same as previous, no need to recalculate things
    return !(location.equals(locationEngine.getLastLocation())
      || (location.getSpeed() <= 0 && location.hasSpeed())
      || location.getAccuracy() >= 100);
  }

  /**
   * Corresponds to ProgressChangeListener object, updating the notification and passing information
   * to the navigation event dispatcher.
   */
  @Override
  public void onNewRouteProgress(Location location, RouteProgress routeProgress) {
    this.routeProgress = routeProgress;

    if (firstProgressUpdate) {
      NavigationMetricsWrapper.departEvent(mapboxNavigation.getSessionState(), routeProgress,
        rawLocation);
      firstProgressUpdate = false;
    }
    if (mapboxNavigation.options().enableNotification()) {
      navNotificationManager.updateDefaultNotification(routeProgress);
    }
    mapboxNavigation.getEventDispatcher().onProgressChange(location, routeProgress);
  }

  /**
   * With each valid and successful rawLocation update, this will get called once the work on the
   * navigation engine thread has finished. Depending on whether or not a milestone gets triggered
   * or not, the navigation event dispatcher will be called to notify the developer.
   */
  @Override
  public void onMilestoneTrigger(List<Milestone> triggeredMilestones, RouteProgress routeProgress) {
    for (Milestone milestone : triggeredMilestones) {
      String instruction = buildInstructionString(routeProgress, milestone);
      mapboxNavigation.getEventDispatcher().onMilestoneEvent(
        routeProgress, instruction, milestone.getIdentifier());
    }
  }

  /**
   * With each valid and successful rawLocation update, this callback gets invoked and depending on
   * whether or not the user is off route, the event dispatcher gets called.
   */
  @Override
  public void onUserOffRoute(Location location, boolean userOffRoute) {
    if (userOffRoute) {
      if (location.getTime() > timeIntervalSinceLastOffRoute
        + TimeUnit.SECONDS.toMillis(mapboxNavigation.options().secondsBeforeReroute())) {
        timeIntervalSinceLastOffRoute = location.getTime();
        if (mapboxNavigation.getSessionState().lastReroutePosition() == null) {
          rerouteSessionsStateUpdate();
        } else {
          if (TurfMeasurement.distance(mapboxNavigation.getSessionState().lastReroutePosition(),
            Position.fromLngLat(location.getLongitude(), location.getLatitude()),
            TurfConstants.UNIT_METERS)
            > mapboxNavigation.options().minimumDistanceBeforeRerouting()) {
            mapboxNavigation.getEventDispatcher().onUserOffRoute(location);
            rerouteSessionsStateUpdate();
          }
        }
      }
    } else {
      timeIntervalSinceLastOffRoute = location.getTime();
    }
  }

  private void rerouteSessionsStateUpdate() {
    recentDistancesFromManeuverInMeters.clear();
    mapboxNavigation.getEventDispatcher().onUserOffRoute(rawLocation);
    mapboxNavigation.setSessionState(
      mapboxNavigation.getSessionState().toBuilder().lastReroutePosition(
        Position.fromLngLat(rawLocation.getLongitude(), rawLocation.getLatitude())).build()
    );
  }

  public void rerouteOccurred() {
    mapboxNavigation.setSessionState(mapboxNavigation.getSessionState().toBuilder()
      .beforeRerouteLocations(Arrays.asList(
        locationBuffer.toArray(new Location[locationBuffer.size()])))
      .routeProgressBeforeReroute(routeProgress)
      .build());
    locationBuffer.clear();
    queuedRerouteEvent = true;

    handler = new Handler();
    runnable = new Runnable() {
      @Override
      public void run() {
        mapboxNavigation.setSessionState(mapboxNavigation.getSessionState().toBuilder()
          .afterRerouteLocations(Arrays.asList(
            locationBuffer.toArray(new Location[locationBuffer.size()])))
          .build());

        locationBuffer.clear();

        NavigationMetricsWrapper.rerouteEvent(mapboxNavigation.getSessionState(), routeProgress,
          rawLocation);
        queuedRerouteEvent = false;
      }
    };
    handler.postDelayed(runnable, TWENTY_SECOND_INTERVAL);
  }

  class LocalBinder extends Binder {
    NavigationService getService() {
      Timber.d("Local binder called.");
      return NavigationService.this;
    }
  }
}
