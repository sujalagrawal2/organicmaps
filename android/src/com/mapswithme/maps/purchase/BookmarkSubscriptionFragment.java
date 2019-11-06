package com.mapswithme.maps.purchase;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.android.billingclient.api.SkuDetails;
import com.mapswithme.maps.Framework;
import com.mapswithme.maps.PrivateVariables;
import com.mapswithme.maps.R;
import com.mapswithme.maps.bookmarks.data.BookmarkManager;
import com.mapswithme.maps.dialog.AlertDialog;
import com.mapswithme.maps.dialog.AlertDialogCallback;
import com.mapswithme.maps.dialog.ResolveFragmentManagerStrategy;
import com.mapswithme.util.ConnectionState;
import com.mapswithme.util.NetworkPolicy;
import com.mapswithme.util.UiUtils;
import com.mapswithme.util.Utils;
import com.mapswithme.util.log.Logger;
import com.mapswithme.util.log.LoggerFactory;
import com.mapswithme.util.statistics.Statistics;

import java.util.Collections;
import java.util.List;

public class BookmarkSubscriptionFragment extends AbstractBookmarkSubscriptionFragment
    implements AlertDialogCallback, PurchaseStateActivator<BookmarkSubscriptionPaymentState>,
               SubscriptionUiChangeListener
{
  static final String EXTRA_FROM = "extra_from";

  private static final Logger LOGGER = LoggerFactory.INSTANCE.getLogger(LoggerFactory.Type.BILLING);
  private static final String TAG = BookmarkSubscriptionFragment.class.getSimpleName();
  private final static String EXTRA_CURRENT_STATE = "extra_current_state";
  private final static String EXTRA_PRODUCT_DETAILS = "extra_product_details";
  private static final int DEF_ELEVATION = 0;

  @SuppressWarnings("NullableProblems")
  @NonNull
  private PurchaseController<PurchaseCallback> mPurchaseController;
  @NonNull
  private final BookmarkSubscriptionCallback mPurchaseCallback = new BookmarkSubscriptionCallback();
  @NonNull
  private BookmarkSubscriptionPaymentState mState = BookmarkSubscriptionPaymentState.NONE;
  @Nullable
  private ProductDetails[] mProductDetails;
  private boolean mValidationResult;

  @Nullable
  @Override
  View onSubscriptionCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                @Nullable Bundle savedInstanceState)
  {
    mPurchaseController = PurchaseFactory.createBookmarksSubscriptionPurchaseController(requireContext());
    if (savedInstanceState != null)
      mPurchaseController.onRestore(savedInstanceState);
    mPurchaseController.initialize(requireActivity());

    View root = inflater.inflate(R.layout.bookmark_subscription_fragment, container, false);
    CardView annualPriceCard = root.findViewById(R.id.annual_price_card);
    CardView monthlyPriceCard = root.findViewById(R.id.monthly_price_card);
    AnnualCardClickListener annualCardListener = new AnnualCardClickListener(monthlyPriceCard,
                                                                             annualPriceCard);
    annualPriceCard.setOnClickListener(annualCardListener);
    MonthlyCardClickListener monthlyCardListener = new MonthlyCardClickListener(monthlyPriceCard,
                                                                                annualPriceCard);
    monthlyPriceCard.setOnClickListener(monthlyCardListener);

    TextView restorePurchasesBtn = root.findViewById(R.id.restore_purchase_btn);
    restorePurchasesBtn.setOnClickListener(v -> openSubscriptionManagementSettings());

    View continueBtn = root.findViewById(R.id.continue_btn);
    continueBtn.setOnClickListener(v -> onContinueButtonClicked());

    annualPriceCard.setSelected(true);
    monthlyPriceCard.setSelected(false);
    annualPriceCard.setCardElevation(getResources().getDimension(R.dimen.margin_base_plus_quarter));

    View termsOfUse = root.findViewById(R.id.term_of_use_link);
    termsOfUse.setOnClickListener(v -> Utils.openUrl(requireActivity(), Framework.nativeGetTermsOfUseLink()));
    View privacyPolicy = root.findViewById(R.id.privacy_policy_link);
    privacyPolicy.setOnClickListener(v -> Utils.openUrl(requireActivity(), Framework.nativeGetPrivacyPolicyLink()));

    Statistics.INSTANCE.trackPurchasePreviewShow(PrivateVariables.bookmarksSubscriptionServerId(),
                                                 PrivateVariables.bookmarksSubscriptionVendor(),
                                                 PrivateVariables.bookmarksSubscriptionYearlyProductId(),
                                                 getExtraFrom());
    return root;
  }

  @Override
  void onSubscriptionDestroyView()
  {
    // Do nothing by default.
  }

  @Nullable
  private String getExtraFrom()
  {
    if (getArguments() == null)
      return null;

    return getArguments().getString(EXTRA_FROM, null);
  }


  private void openSubscriptionManagementSettings()
  {
    Utils.openUrl(requireContext(), "https://play.google.com/store/account/subscriptions");
    Statistics.INSTANCE.trackPurchaseEvent(Statistics.EventName.INAPP_PURCHASE_PREVIEW_RESTORE,
                                           PrivateVariables.bookmarksSubscriptionServerId());
  }

  private void onContinueButtonClicked()
  {
    BookmarkManager.INSTANCE.pingBookmarkCatalog();
    activateState(BookmarkSubscriptionPaymentState.PINGING);

    Statistics.INSTANCE.trackPurchaseEvent(Statistics.EventName.INAPP_PURCHASE_PREVIEW_PAY,
                                           PrivateVariables.bookmarksSubscriptionServerId(),
                                           Statistics.STATISTICS_CHANNEL_REALTIME);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    if (savedInstanceState != null)
    {
      BookmarkSubscriptionPaymentState savedState
          = BookmarkSubscriptionPaymentState.values()[savedInstanceState.getInt(EXTRA_CURRENT_STATE)];
      ProductDetails[] productDetails
          = (ProductDetails[]) savedInstanceState.getParcelableArray(EXTRA_PRODUCT_DETAILS);
      if (productDetails != null)
        mProductDetails = productDetails;

      activateState(savedState);
      return;
    }

    activateState(BookmarkSubscriptionPaymentState.CHECK_NETWORK_CONNECTION);
  }

  private void queryProductDetails()
  {
    mPurchaseController.queryProductDetails();
  }

  @Override
  public void onStart()
  {
    super.onStart();
    mPurchaseController.addCallback(mPurchaseCallback);
    mPurchaseCallback.attach(this);
  }

  @Override
  public void onStop()
  {
    super.onStop();
    mPurchaseController.removeCallback();
    mPurchaseCallback.detach();
  }

  @Override
  public void activateState(@NonNull BookmarkSubscriptionPaymentState state)
  {
    if (state == mState)
      return;

    LOGGER.i(TAG, "Activate state: " + state);
    mState = state;
    mState.activate(this);
  }

  private void handleProductDetails(@NonNull List<SkuDetails> details)
  {
    mProductDetails = new ProductDetails[PurchaseUtils.Period.values().length];
    for (SkuDetails sku: details)
    {
      PurchaseUtils.Period period = PurchaseUtils.Period.valueOf(sku.getSubscriptionPeriod());
      mProductDetails[period.ordinal()] = PurchaseUtils.toProductDetails(sku);
    }
  }

  private void updatePaymentButtons()
  {
    updateYearlyButton();
    updateMonthlyButton();
  }

  private void updateYearlyButton()
  {
    ProductDetails details = getProductDetailsForPeriod(PurchaseUtils.Period.P1Y);
    String price = Utils.formatCurrencyString(details.getPrice(), details.getCurrencyCode());
    TextView priceView = getViewOrThrow().findViewById(R.id.annual_price);
    priceView.setText(price);
    TextView savingView = getViewOrThrow().findViewById(R.id.sale);
    String text = getString(R.string.annual_save_component, calculateYearlySaving());
    savingView.setText(text);
  }

  private void updateMonthlyButton()
  {
    ProductDetails details = getProductDetailsForPeriod(PurchaseUtils.Period.P1M);
    String price = Utils.formatCurrencyString(details.getPrice(), details.getCurrencyCode());
    TextView priceView = getViewOrThrow().findViewById(R.id.monthly_price);
    priceView.setText(price);
  }

  private int calculateYearlySaving()
  {
    float pricePerMonth = getProductDetailsForPeriod(PurchaseUtils.Period.P1M).getPrice();
    float pricePerYear = getProductDetailsForPeriod(PurchaseUtils.Period.P1Y).getPrice();
    return (int) (100 * (1 - pricePerYear / (pricePerMonth * PurchaseUtils.MONTHS_IN_YEAR)));
  }

  @NonNull
  private ProductDetails getProductDetailsForPeriod(@NonNull PurchaseUtils.Period period)
  {
    if (mProductDetails == null)
      throw new AssertionError("Product details must be exist at this moment!");
    return mProductDetails[period.ordinal()];
  }

  @Override
  public void onAlertDialogPositiveClick(int requestCode, int which)
  {
    if (requestCode == PurchaseUtils.REQ_CODE_NO_NETWORK_CONNECTION_DIALOG)
    {
      dismissOutdatedNoNetworkDialog();
      activateState(BookmarkSubscriptionPaymentState.NONE);
      activateState(BookmarkSubscriptionPaymentState.CHECK_NETWORK_CONNECTION);
    }
  }

  @Override
  public void onAlertDialogNegativeClick(int requestCode, int which)
  {
    if (requestCode == PurchaseUtils.REQ_CODE_NO_NETWORK_CONNECTION_DIALOG)
      requireActivity().finish();
  }

  @Override
  public void onAlertDialogCancel(int requestCode)
  {
    if (requestCode == PurchaseUtils.REQ_CODE_NO_NETWORK_CONNECTION_DIALOG)
      requireActivity().finish();
  }

  private void handleActivationResult(boolean result)
  {
    mValidationResult = result;
  }

  private void finishValidation()
  {
    if (mValidationResult)
      requireActivity().setResult(Activity.RESULT_OK);

    requireActivity().finish();
  }

  @Override
  public boolean onBackPressed()
  {
    Statistics.INSTANCE.trackPurchaseEvent(Statistics.EventName.INAPP_PURCHASE_PREVIEW_CANCEL,
                                           PrivateVariables.bookmarksSubscriptionServerId());
    return super.onBackPressed();
  }

  private void launchPurchaseFlow()
  {
    CardView annualCard = getViewOrThrow().findViewById(R.id.annual_price_card);
    PurchaseUtils.Period period = annualCard.getCardElevation() > 0 ? PurchaseUtils.Period.P1Y
                                                                    : PurchaseUtils.Period.P1M;
    ProductDetails details = getProductDetailsForPeriod(period);
    mPurchaseController.launchPurchaseFlow(details.getProductId());
  }

  @Override
  protected int getProgressMessageId()
  {
    return R.string.please_wait;
  }

  @Override
  public void onAuthorizationFinish(boolean success)
  {
    hideProgress();
    if (!success)
    {
      Toast.makeText(requireContext(), R.string.profile_authorization_error, Toast.LENGTH_LONG)
           .show();
      return;
    }

    launchPurchaseFlow();
  }

  @Override
  public void onAuthorizationStart()
  {
    showProgress();
  }

  @Override
  public void onSocialAuthenticationCancel(@Framework.AuthTokenType int type)
  {
    LOGGER.i(TAG, "Social authentication cancelled,  auth type = " + type);
  }

  @Override
  public void onSocialAuthenticationError(@Framework.AuthTokenType int type, @Nullable String error)
  {
    LOGGER.w(TAG, "Social authentication error = " + error + ",  auth type = " + type);
  }

  private void onNetworkCheckPassed()
  {
    activateState(BookmarkSubscriptionPaymentState.PRODUCT_DETAILS_LOADING);
  }

  private void dismissOutdatedNoNetworkDialog()
  {
    ResolveFragmentManagerStrategy strategy
        = AlertDialog.FragManagerStrategyType.ACTIVITY_FRAGMENT_MANAGER.getValue();
    FragmentManager manager = strategy.resolve(this);
    Fragment outdatedInstance = manager.findFragmentByTag(PurchaseUtils.NO_NETWORK_CONNECTION_DIALOG_TAG);
    if (outdatedInstance == null)
      return;

    manager.beginTransaction().remove(outdatedInstance).commitAllowingStateLoss();
    manager.executePendingTransactions();
  }

  @Override
  public void onReset()
  {
    hideAllUi();
  }

  @Override
  public void onProductDetailsLoading()
  {
    showRootScreenProgress();
    queryProductDetails();
  }

  @Override
  public void onProductDetailsFailure()
  {
    PurchaseUtils.showProductDetailsFailureDialog(this, getClass().getSimpleName());
  }

  @Override
  public void onPaymentFailure()
  {
    PurchaseUtils.showPaymentFailureDialog(this, getClass().getSimpleName());
  }

  @Override
  public void onPriceSelection()
  {
    hideRootScreenProgress();
    updatePaymentButtons();
  }

  @Override
  public void onValidating()
  {
    showButtonProgress();
  }

  @Override
  public void onValidationFinish()
  {
    hideButtonProgress();
    finishValidation();
  }

  @Override
  public void onPinging()
  {
    showButtonProgress();
  }

  @Override
  public void onPingFinish()
  {
    super.onPingFinish();
    hideButtonProgress();
  }

  @Override
  public void onCheckNetworkConnection()
  {
    if (ConnectionState.isConnected())
      NetworkPolicy.checkNetworkPolicy(requireFragmentManager(),
                                       this::onNetworkPolicyResult, true);
    else
      PurchaseUtils.showNoConnectionDialog(this);
  }

  private void onNetworkPolicyResult(@NonNull NetworkPolicy policy)
  {
    if (policy.canUseNetwork())
      onNetworkCheckPassed();
    else
      requireActivity().finish();
  }

  private void showButtonProgress()
  {
    UiUtils.hide(getViewOrThrow(), R.id.continue_btn);
    UiUtils.show(getViewOrThrow(), R.id.progress);
  }

  private void hideButtonProgress()
  {
    UiUtils.hide(getViewOrThrow(), R.id.progress);
    UiUtils.show(getViewOrThrow(), R.id.continue_btn);
  }

  private void showRootScreenProgress()
  {
    UiUtils.show(getViewOrThrow(), R.id.root_screen_progress);
    UiUtils.hide(getViewOrThrow(), R.id.content_view);
  }

  private void hideRootScreenProgress()
  {
    UiUtils.hide(getViewOrThrow(), R.id.root_screen_progress);
    UiUtils.show(getViewOrThrow(), R.id.content_view);
  }

  private void hideAllUi()
  {
    UiUtils.hide(getViewOrThrow(), R.id.root_screen_progress, R.id.content_view);
  }

  private class AnnualCardClickListener implements View.OnClickListener
  {
    @NonNull
    private final CardView mMonthlyPriceCard;

    @NonNull
    private final CardView mAnnualPriceCard;

    AnnualCardClickListener(@NonNull CardView monthlyPriceCard,
                            @NonNull CardView annualPriceCard)
    {
      mMonthlyPriceCard = monthlyPriceCard;
      mAnnualPriceCard = annualPriceCard;
    }

    @Override
    public void onClick(View v)
    {
      mMonthlyPriceCard.setCardElevation(DEF_ELEVATION);
      mAnnualPriceCard.setCardElevation(getResources().getDimension(R.dimen.margin_base_plus_quarter));

      if (!mAnnualPriceCard.isSelected())
        Statistics.INSTANCE.trackPurchasePreviewSelect(PrivateVariables.bookmarksSubscriptionServerId(),
                                                       PrivateVariables.bookmarksSubscriptionYearlyProductId());

      mMonthlyPriceCard.setSelected(false);
      mAnnualPriceCard.setSelected(true);
    }
  }

  private class MonthlyCardClickListener implements View.OnClickListener
  {
    @NonNull
    private final CardView mMonthlyPriceCard;

    @NonNull
    private final CardView mAnnualPriceCard;

    MonthlyCardClickListener(@NonNull CardView monthlyPriceCard,
                             @NonNull CardView annualPriceCard)
    {
      mMonthlyPriceCard = monthlyPriceCard;
      mAnnualPriceCard = annualPriceCard;
    }

    @Override
    public void onClick(View v)
    {
      mMonthlyPriceCard.setCardElevation(getResources().getDimension(R.dimen.margin_base_plus_quarter));
      mAnnualPriceCard.setCardElevation(DEF_ELEVATION);

      if (!mMonthlyPriceCard.isSelected())
        Statistics.INSTANCE.trackPurchasePreviewSelect(PrivateVariables.bookmarksSubscriptionServerId(),
                                                       PrivateVariables.bookmarksSubscriptionMonthlyProductId());

      mMonthlyPriceCard.setSelected(true);
      mAnnualPriceCard.setSelected(false);
    }
  }

  private static class BookmarkSubscriptionCallback
      extends StatefulPurchaseCallback<BookmarkSubscriptionPaymentState, BookmarkSubscriptionFragment>
      implements PurchaseCallback
  {
    @Nullable
    private List<SkuDetails> mPendingDetails;
    private Boolean mPendingValidationResult;

    @Override
    public void onProductDetailsLoaded(@NonNull List<SkuDetails> details)
    {
      if (PurchaseUtils.hasIncorrectSkuDetails(details))
      {
        activateStateSafely(BookmarkSubscriptionPaymentState.PRODUCT_DETAILS_FAILURE);
        return;
      }

      if (getUiObject() == null)
        mPendingDetails = Collections.unmodifiableList(details);
      else
        getUiObject().handleProductDetails(details);
      activateStateSafely(BookmarkSubscriptionPaymentState.PRICE_SELECTION);
    }

    @Override
    public void onPaymentFailure(int error)
    {
      Statistics.INSTANCE.trackPurchaseStoreError(PrivateVariables.bookmarksSubscriptionServerId(),
                                                  error);
      activateStateSafely(BookmarkSubscriptionPaymentState.PAYMENT_FAILURE);
    }

    @Override
    public void onProductDetailsFailure()
    {
      activateStateSafely(BookmarkSubscriptionPaymentState.PRODUCT_DETAILS_FAILURE);
    }

    @Override
    public void onStoreConnectionFailed()
    {
      activateStateSafely(BookmarkSubscriptionPaymentState.PRODUCT_DETAILS_FAILURE);
    }

    @Override
    public void onValidationStarted()
    {
      Statistics.INSTANCE.trackPurchaseEvent(Statistics.EventName.INAPP_PURCHASE_STORE_SUCCESS,
                                             PrivateVariables.bookmarksSubscriptionServerId());
      activateStateSafely(BookmarkSubscriptionPaymentState.VALIDATION);
    }

    @Override
    public void onValidationFinish(boolean success)
    {
      if (getUiObject() == null)
        mPendingValidationResult = success;
      else
        getUiObject().handleActivationResult(success);

      activateStateSafely(BookmarkSubscriptionPaymentState.VALIDATION_FINISH);
    }

    @Override
    void onAttach(@NonNull BookmarkSubscriptionFragment bookmarkSubscriptionFragment)
    {
      if (mPendingDetails != null)
      {
        bookmarkSubscriptionFragment.handleProductDetails(mPendingDetails);
        mPendingDetails = null;
      }

      if (mPendingValidationResult != null)
      {
        bookmarkSubscriptionFragment.handleActivationResult(mPendingValidationResult);
        mPendingValidationResult = null;
      }
    }
  }
}
