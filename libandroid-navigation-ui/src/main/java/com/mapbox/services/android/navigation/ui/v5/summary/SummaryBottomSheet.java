package com.mapbox.services.android.navigation.ui.v5.summary;

import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.Observer;
import android.content.Context;
import android.support.annotation.Nullable;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.mapbox.services.android.navigation.ui.v5.NavigationViewModel;
import com.mapbox.services.android.navigation.ui.v5.R;
import com.mapbox.services.android.navigation.ui.v5.summary.list.DirectionListAdapter;
import com.mapbox.services.android.navigation.ui.v5.summary.list.DirectionViewHolder;
import com.mapbox.services.android.navigation.v5.navigation.NavigationConstants;
import com.mapbox.services.android.navigation.v5.routeprogress.ProgressChangeListener;
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress;

import java.text.DecimalFormat;

/**
 * A view with {@link android.support.design.widget.BottomSheetBehavior}
 * that displays route summary information during navigation.
 * <p>
 * Can be expanded / collapsed to show / hide the list of
 * directions.
 *
 * @since 0.6.0
 */
public class SummaryBottomSheet extends FrameLayout {

  private static final String EMPTY_STRING = "";
  private static final int SCROLL_DIRECTION_UP = -1;

  private TextView distanceRemainingText;
  private TextView timeRemainingText;
  private TextView arrivalTimeText;
  private ProgressBar rerouteProgressBar;
  private RecyclerView rvDirections;
  private View rvShadow;

  private DecimalFormat decimalFormat;
  private DirectionListAdapter directionListAdapter;
  private boolean isRerouting;

  public SummaryBottomSheet(Context context) {
    this(context, null);
  }

  public SummaryBottomSheet(Context context, AttributeSet attrs) {
    this(context, attrs, -1);
  }

  public SummaryBottomSheet(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  /**
   * After the layout inflates, binds all necessary views,
   * create a {@link RecyclerView} for the list of directions,
   * and a new {@link DecimalFormat} for formatting distance remaining.
   */
  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    bind();
    initDirectionsRecyclerView();
    initDecimalFormat();
  }

  public void subscribe(NavigationViewModel navigationViewModel) {
    navigationViewModel.summaryModel.observe((LifecycleOwner) getContext(), new Observer<SummaryModel>() {
      @Override
      public void onChanged(@Nullable SummaryModel summaryModel) {
        if (summaryModel != null && !isRerouting) {
          arrivalTimeText.setText(summaryModel.getArrivalTime());
          timeRemainingText.setText(summaryModel.getTimeRemaining());
          distanceRemainingText.setText(summaryModel.getDistanceRemaining());
          updateSteps(summaryModel.getProgress());
          updateFirstViewHolder();
        }
      }
    });
    navigationViewModel.isOffRoute.observe((LifecycleOwner) getContext(), new Observer<Boolean>() {
      @Override
      public void onChanged(@Nullable Boolean isOffRoute) {
        if (isOffRoute != null) {
          isRerouting = isOffRoute;
          if (isRerouting) {
            showRerouteState();
          } else {
            hideRerouteState();
          }
        }
      }
    });
  }

  /**
   * Called in {@link ProgressChangeListener}, creates a new model and then
   * uses it to update the views.
   *
   * @param routeProgress used to provide navigation / routeProgress data
   */
  @SuppressWarnings("UnusedDeclaration")
  public void update(RouteProgress routeProgress) {
    if (routeProgress != null && !isRerouting) {
      SummaryModel model = new SummaryModel(routeProgress, decimalFormat);
      arrivalTimeText.setText(model.getArrivalTime());
      timeRemainingText.setText(model.getTimeRemaining());
      distanceRemainingText.setText(model.getDistanceRemaining());
      updateSteps(routeProgress);
      updateFirstViewHolder();
    }
  }

  /**
   * Shows the reroute progress bar and clears current text views.
   * Also sets boolean to rerouting state so views do not
   * continue to update while we are fetching a new route.
   *
   * @since 0.6.0
   */
  public void showRerouteState() {
    rerouteProgressBar.setVisibility(VISIBLE);
    clearViews();
  }

  /**
   * Hides the reroute progress bar and sets
   * rerouting state to false to text will begin updating again.
   *
   * @since 0.6.0
   */
  public void hideRerouteState() {
    rerouteProgressBar.setVisibility(INVISIBLE);
  }

  /**
   * Inflates this layout needed for this view.
   */
  private void init() {
    inflate(getContext(), R.layout.summary_bottomsheet_layout, this);
  }

  /**
   * Finds and binds all necessary views
   */
  private void bind() {
    distanceRemainingText = findViewById(R.id.distanceRemainingText);
    timeRemainingText = findViewById(R.id.timeRemainingText);
    arrivalTimeText = findViewById(R.id.arrivalTimeText);
    rerouteProgressBar = findViewById(R.id.rerouteProgressBar);
    rvDirections = findViewById(R.id.rvDirections);
    rvShadow = findViewById(R.id.rvShadow);
  }

  /**
   * Sets up the {@link RecyclerView} that is used to display the list of directions.
   */
  private void initDirectionsRecyclerView() {
    directionListAdapter = new DirectionListAdapter();
    rvDirections.setAdapter(directionListAdapter);
    rvDirections.setHasFixedSize(true);
    rvDirections.setNestedScrollingEnabled(true);
    rvDirections.setItemAnimator(new DefaultItemAnimator());
    rvDirections.setLayoutManager(new LinearLayoutManager(getContext()));
    rvDirections.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
        super.onScrolled(recyclerView, dx, dy);
        rvShadow.setVisibility(rvDirections.canScrollVertically(SCROLL_DIRECTION_UP) ? View.VISIBLE : View.INVISIBLE);
      }
    });
  }

  /**
   * Initializes decimal format to be used to populate views with
   * distance remaining.
   */
  private void initDecimalFormat() {
    decimalFormat = new DecimalFormat(NavigationConstants.DECIMAL_FORMAT);
  }

  /**
   * Clears all {@link View}s.
   */
  private void clearViews() {
    arrivalTimeText.setText(EMPTY_STRING);
    timeRemainingText.setText(EMPTY_STRING);
    distanceRemainingText.setText(EMPTY_STRING);
    directionListAdapter.clear();
  }

  /**
   * Used to update the directions list with the current steps.
   *
   * @param routeProgress to provide the current steps
   */
  private void updateSteps(RouteProgress routeProgress) {
    directionListAdapter.updateSteps(routeProgress);
  }

  /**
   * Sets the first {@link android.support.v7.widget.RecyclerView.ViewHolder}
   * to bold for emphasis.
   */
  private void updateFirstViewHolder() {
    if (rvDirections.findViewHolderForAdapterPosition(0) != null) {
      ((DirectionViewHolder) rvDirections.findViewHolderForAdapterPosition(0)).updateFirstViewHolder();
    }
  }
}
