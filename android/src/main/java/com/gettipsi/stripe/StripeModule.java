package com.gettipsi.stripe;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.gettipsi.stripe.util.ArgCheck;
import com.gettipsi.stripe.util.Converters;
import com.gettipsi.stripe.util.Fun0;
import com.google.android.gms.wallet.WalletConstants;
import com.stripe.android.ApiResultCallback;
import com.stripe.android.AppInfo;
import com.stripe.android.PaymentConfiguration;
import com.stripe.android.PaymentIntentResult;
import com.stripe.android.SetupIntentResult;
import com.stripe.android.Stripe;
import com.stripe.android.model.Address;
import com.stripe.android.model.ConfirmPaymentIntentParams;
import com.stripe.android.model.ConfirmSetupIntentParams;
import com.stripe.android.model.PaymentMethod;
import com.stripe.android.model.PaymentMethodCreateParams;
import com.stripe.android.model.Source;
import com.stripe.android.model.SourceParams;
import com.stripe.android.model.StripeIntent;
import com.stripe.android.model.Token;

import java.util.HashMap;
import java.util.Map;

import de.jonasbark.stripepayment.StripeDialog;
import io.flutter.plugin.common.PluginRegistry;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import static com.gettipsi.stripe.Errors.AUTHENTICATION_FAILED;
import static com.gettipsi.stripe.Errors.CANCELLED;
import static com.gettipsi.stripe.Errors.FAILED;
import static com.gettipsi.stripe.Errors.UNEXPECTED;
import static com.gettipsi.stripe.Errors.getDescription;
import static com.gettipsi.stripe.Errors.getErrorCode;
import static com.gettipsi.stripe.Errors.toErrorCode;
import static com.gettipsi.stripe.util.Converters.convertPaymentIntentResultToWritableMap;
import static com.gettipsi.stripe.util.Converters.convertPaymentMethodToWritableMap;
import static com.gettipsi.stripe.util.Converters.convertSetupIntentResultToWritableMap;
import static com.gettipsi.stripe.util.Converters.convertSourceToWritableMap;
import static com.gettipsi.stripe.util.Converters.convertTokenToWritableMap;
import static com.gettipsi.stripe.util.Converters.createBankAccount;
import static com.gettipsi.stripe.util.Converters.createCard;
import static com.gettipsi.stripe.util.Converters.getBooleanOrNull;
import static com.gettipsi.stripe.util.Converters.getMapOrNull;
import static com.gettipsi.stripe.util.Converters.getStringOrNull;
import static com.gettipsi.stripe.util.InitializationOptions.ANDROID_PAY_MODE_KEY;
import static com.gettipsi.stripe.util.InitializationOptions.ANDROID_PAY_MODE_PRODUCTION;
import static com.gettipsi.stripe.util.InitializationOptions.ANDROID_PAY_MODE_TEST;
import static com.gettipsi.stripe.util.InitializationOptions.PUBLISHABLE_KEY;
import static com.stripe.android.model.StripeIntent.Status.Canceled;
import static com.stripe.android.model.StripeIntent.Status.RequiresAction;
import static com.stripe.android.model.StripeIntent.Status.RequiresCapture;
import static com.stripe.android.model.StripeIntent.Status.RequiresConfirmation;
import static com.stripe.android.model.StripeIntent.Status.Succeeded;

public class StripeModule extends ReactContextBaseJavaModule {


  private static final String MODULE_NAME = StripeModule.class.getSimpleName();

  // If you change these, make sure to also change:
  //  ios/TPSStripe/TPSStripeManager
  // Relevant Docs:
  // - https://stripe.dev/stripe-ios/docs/Classes/STPAppInfo.html https://stripe.dev/stripe-android/com/stripe/android/AppInfo.html
  // - https://stripe.com/docs/building-plugins#setappinfo
  private static final String APP_INFO_NAME    = "flutter_stripe_payment";
  private static final String APP_INFO_URL     = "https://github.com/jonasbark/flutter_stripe_payment";
  private static final String APP_INFO_VERSION = "1.x";
  public static final String CLIENT_SECRET = "clientSecret";

  private static StripeModule sInstance = null;

  @Nullable
  private String mStripeAccountId = null;

  public static StripeModule getInstance() {
    return sInstance;
  }

  public Stripe getStripe() {
    return mStripe;
  }

  @Nullable
  private Promise mCreateSourcePromise;

  @Nullable
  private Source mCreatedSource;

  private String mPublicKey;
  private Stripe mStripe;
  private PayFlow mPayFlow;
  private ReadableMap mErrorCodes;

  private final PluginRegistry.ActivityResultListener mActivityEventListener = new PluginRegistry.ActivityResultListener() {

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
      boolean handled = getPayFlow().onActivityResult(activity, requestCode, resultCode, data);
      return handled;
    }
  };

  public StripeModule(PluginRegistry.Registrar registrar, Activity activity) {
    super(activity, registrar);
    registrar.addActivityResultListener(mActivityEventListener);

    sInstance = this;
  }

  @ReactMethod
  public void init(@NonNull ReadableMap options, @NonNull ReadableMap errorCodes) {
    ArgCheck.nonNull(options);

    String newPubKey = Converters.getStringOrNull(options, PUBLISHABLE_KEY);
    String newAndroidPayMode = Converters.getStringOrNull(options, ANDROID_PAY_MODE_KEY);

    if (newPubKey != null && !TextUtils.equals(newPubKey, mPublicKey)) {
      ArgCheck.notEmptyString(newPubKey);

      mPublicKey = newPubKey;
      Stripe.setAppInfo(AppInfo.create(APP_INFO_NAME, APP_INFO_VERSION, APP_INFO_URL));
      mStripe = new Stripe(getReactApplicationContext(), mPublicKey);
      getPayFlow().setPublishableKey(mPublicKey);
      PaymentConfiguration.init(getReactApplicationContext(), mPublicKey);
    }

    if (newAndroidPayMode != null) {
      ArgCheck.isTrue(ANDROID_PAY_MODE_TEST.equals(newAndroidPayMode) || ANDROID_PAY_MODE_PRODUCTION.equals(newAndroidPayMode));

      getPayFlow().setEnvironment(androidPayModeToEnvironment(newAndroidPayMode));
    }

    if (mErrorCodes == null) {
      mErrorCodes = errorCodes;
      getPayFlow().setErrorCodes(errorCodes);
    }
  }

  private PayFlow getPayFlow() {
    if (mPayFlow == null) {
      mPayFlow = PayFlow.create(
        new Fun0<Activity>() { public Activity call() {
          return getCurrentActivity();
        }}
      );
    }

    return mPayFlow;
  }

  private static int androidPayModeToEnvironment(@NonNull String androidPayMode) {
    ArgCheck.notEmptyString(androidPayMode);
    return ANDROID_PAY_MODE_TEST.equals(androidPayMode.toLowerCase()) ? WalletConstants.ENVIRONMENT_TEST : WalletConstants.ENVIRONMENT_PRODUCTION;
  }

  @ReactMethod
  public void deviceSupportsAndroidPay(final Promise promise) {
    getPayFlow().deviceSupportsAndroidPay(false, promise);
  }

  @ReactMethod
  public void canMakeAndroidPayPayments(final Promise promise) {
    getPayFlow().deviceSupportsAndroidPay(true, promise);
  }

  @ReactMethod
  public void setStripeAccount(final String stripeAccount) {
    ArgCheck.notEmptyString(mPublicKey);
    mStripeAccountId = stripeAccount;
    if (stripeAccount == null) {
      mStripe = new Stripe(getReactApplicationContext(), mPublicKey);
    } else {
      mStripe = new Stripe(getReactApplicationContext(), mPublicKey, stripeAccount);
    }
  }

  @ReactMethod
  public void createTokenWithCard(final ReadableMap cardData, final Promise promise) {
    try {
      ArgCheck.nonNull(mStripe);
      ArgCheck.notEmptyString(mPublicKey);

      mStripe.createCardToken(
        createCard(cardData),
        //! FIX IDEMPOTENCY ERROR - JUN 27, 21
        // mPublicKey,
        new ApiResultCallback<Token>() {
          public void onSuccess(Token token) {
            promise.resolve(convertTokenToWritableMap(token));
          }
          public void onError(Exception error) {
            error.printStackTrace();
            promise.reject(toErrorCode(error), error.getMessage());
          }
        });
    } catch (Exception e) {
      promise.reject(toErrorCode(e), e.getMessage());
    }
  }

  @ReactMethod
  public void createTokenWithBankAccount(final ReadableMap accountData, final Promise promise) {
    try {
      ArgCheck.nonNull(mStripe);
      ArgCheck.notEmptyString(mPublicKey);

      mStripe.createBankAccountToken(
        createBankAccount(accountData),
        mPublicKey,
        null,
        new ApiResultCallback<Token>() {
          public void onSuccess(Token token) {
            promise.resolve(convertTokenToWritableMap(token));
          }
          public void onError(Exception error) {
            error.printStackTrace();
            promise.reject(toErrorCode(error), error.getMessage());
          }
        });
    } catch (Exception e) {
      promise.reject(toErrorCode(e), e.getMessage());
    }
  }

  @ReactMethod
  public void paymentRequestWithCardForm(ReadableMap params, final Promise promise) {
    Activity currentActivity = getCurrentActivity();
    try {
      ArgCheck.nonNull(currentActivity);
      ArgCheck.notEmptyString(mPublicKey);
      final StripeDialog cardDialog = StripeDialog.newInstance(
        "",
              mStripeAccountId
      );
      cardDialog.setStripeInstance(mStripe);
      cardDialog.setTokenListener(new Function1<PaymentMethod, Unit>() {
        @Override
        public Unit invoke(PaymentMethod paymentMethod) {
          promise.resolve(Converters.convertPaymentMethodToWritableMap(paymentMethod));
          return Unit.INSTANCE;
        }
      });
      cardDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
        @Override
        public void onCancel(DialogInterface dialog) {
          promise.reject(CANCELLED, CANCELLED);
        }
      });
      cardDialog.show(currentActivity.getFragmentManager(), "AddNewCard");
    } catch (Exception e) {
      promise.reject(toErrorCode(e), e.getMessage());
    }
  }

  @ReactMethod
  public void paymentRequestWithAndroidPay(final ReadableMap payParams, final Promise promise) {
    getPayFlow().paymentRequestWithAndroidPay(payParams, promise);
  }

  private void attachPaymentResultActivityListener(final Promise promise) {
    ActivityEventListener ael = new BaseActivityEventListener() {

      @Override
      public void onActivityResult(Activity a, int requestCode, int resultCode, Intent data) {
        final ActivityEventListener ael = this;

        mStripe.onPaymentResult(requestCode, data, new ApiResultCallback<PaymentIntentResult>() {
          @Override
          public void onSuccess(@NonNull PaymentIntentResult result) {
            removeActivityEventListener(ael);

            StripeIntent.Status resultingStatus = result.getIntent().getStatus();

            if (Succeeded.equals(resultingStatus) ||
                RequiresCapture.equals(resultingStatus) ||
                RequiresConfirmation.equals(resultingStatus)) {
              promise.resolve(convertPaymentIntentResultToWritableMap(result));
            } else {
              if (Canceled.equals(resultingStatus) ||
                  RequiresAction.equals(resultingStatus)
              ) {
                promise.reject(CANCELLED, CANCELLED);      // TODO - normalize the message
              } else {
                promise.reject(FAILED, FAILED);
              }
            }
          }

          @Override
          public void onError(@NonNull Exception e) {
            removeActivityEventListener(ael);
            e.printStackTrace();
            promise.reject(toErrorCode(e), e.getMessage());
          }
        });
      }

      @Override
      public void onActivityResult(int requestCode, int resultCode, Intent data) {
        onActivityResult(null, requestCode, resultCode, data);
      }
    };
    addActivityEventListener(ael);
  }

  private void attachSetupResultActivityListener(final Promise promise) {
    ActivityEventListener ael = new BaseActivityEventListener() {
      @Override
      public void onActivityResult(Activity a, int requestCode, int resultCode, Intent data) {
        final ActivityEventListener ael = this;

        mStripe.onSetupResult(requestCode, data, new ApiResultCallback<SetupIntentResult>() {
          @Override
          public void onSuccess(@NonNull SetupIntentResult result) {
            removeActivityEventListener(ael);

            try {
              switch (result.getIntent().getStatus()) {
                case Canceled:
                  // The Setup Intent was canceled, so reject the promise with a predefined code.
                  promise.reject(CANCELLED, "The SetupIntent was canceled by the user.");
                  break;
                case RequiresAction:
                case RequiresPaymentMethod:
                  promise.reject(AUTHENTICATION_FAILED, "The user failed authentication.");
                  break;
                case Succeeded:
                  promise.resolve(convertSetupIntentResultToWritableMap(result));
                  break;
                case RequiresCapture:
                case RequiresConfirmation:
                default:
                  promise.reject(UNEXPECTED, "Unexpected state");
              }
            } catch (Exception e) {
              promise.reject(UNEXPECTED, "Unexpected error");
            }
          }

          @Override
          public void onError(@NonNull Exception e) {
            removeActivityEventListener(ael);
            e.printStackTrace();
            promise.reject(toErrorCode(e), e.getMessage());
          }
        });
      }

      @Override
      public void onActivityResult(int requestCode, int resultCode, Intent data) {
        onActivityResult(null, requestCode, resultCode, data);
      }
    };
    addActivityEventListener(ael);
  }

  @ReactMethod
  public void confirmPaymentIntent(final ReadableMap options, final Promise promise) {
    attachPaymentResultActivityListener(promise);

    Activity activity = getCurrentActivity();
    if (activity != null) {
      mStripe.confirmPayment(activity, extractConfirmPaymentIntentParams(options));
    }
  }

  @ReactMethod
  public void authenticatePaymentIntent(final ReadableMap options, final Promise promise) {
    attachPaymentResultActivityListener(promise);

    String clientSecret = options.getString(CLIENT_SECRET);
    Activity activity = getCurrentActivity();
    if (activity != null) {
      mStripe.authenticatePayment(activity, clientSecret);
    }
  }

  @ReactMethod
  public void confirmSetupIntent(final ReadableMap options, final Promise promise) {
    attachSetupResultActivityListener(promise);

    Activity activity = getCurrentActivity();
    if (activity != null) {
      mStripe.confirmSetupIntent(activity, extractConfirmSetupIntentParams(options));
    }
  }

  @ReactMethod
  public void authenticateSetupIntent(final ReadableMap options, final Promise promise) {
    attachSetupResultActivityListener(promise);

    String clientSecret = options.getString(CLIENT_SECRET);
    Activity activity = getCurrentActivity();
    if (activity != null) {
      mStripe.authenticateSetup(activity, clientSecret);
    }
  }


  @ReactMethod
  public void createPaymentMethod(final ReadableMap options, final Promise promise) {

    PaymentMethodCreateParams pmcp = extractPaymentMethodCreateParams(options);

    mStripe.createPaymentMethod(pmcp, new ApiResultCallback<PaymentMethod>() {

      @Override
      public void onError(Exception error) {
        promise.reject(toErrorCode(error), error.getMessage());
      }

      @Override
      public void onSuccess(PaymentMethod paymentMethod) {
        promise.resolve(convertPaymentMethodToWritableMap(paymentMethod));
      }
    });
  }


  @ReactMethod
  public void createSourceWithParams(final ReadableMap options, final Promise promise) {

    SourceParams sourceParams = extractSourceParams(options);

    ArgCheck.nonNull(sourceParams);

    mStripe.createSource(sourceParams, new ApiResultCallback<Source>() {
      @Override
      public void onError(Exception error) {
        promise.reject(toErrorCode(error));
      }

      @Override
      public void onSuccess(Source source) {
        if (Source.Flow.Redirect.equals(source.getFlow())) {
          Activity currentActivity = getCurrentActivity();
          if (currentActivity == null) {
            promise.reject(
              getErrorCode(mErrorCodes, "activityUnavailable"),
              getDescription(mErrorCodes, "activityUnavailable")
            );
          } else {
            mCreateSourcePromise = promise;
            mCreatedSource = source;
            String redirectUrl = source.getRedirect().getUrl();
            Intent browserIntent = new Intent(currentActivity, OpenBrowserActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(OpenBrowserActivity.EXTRA_URL, redirectUrl);
            currentActivity.startActivity(browserIntent);
          }
        } else {
          promise.resolve(convertSourceToWritableMap(source));
        }
      }
    });
  }

  private ConfirmSetupIntentParams extractConfirmSetupIntentParams(final ReadableMap options) {
    ReadableMap paymentMethod = getMapOrNull(options, "paymentMethod");
    String paymentMethodId = getStringOrNull(options, "paymentMethodId");
    String returnURL = getStringOrNull(options, "returnURL");
    String clientSecret = options.getString("clientSecret");
    ConfirmSetupIntentParams csip = null;
    if (returnURL ==  null) {
      returnURL = "stripejs://use_stripe_sdk/return_url";
    }

    if (paymentMethod != null) {
      csip = ConfirmSetupIntentParams.create(extractPaymentMethodCreateParams(paymentMethod),
        clientSecret, returnURL);
    } else if (paymentMethodId != null) {
      csip = ConfirmSetupIntentParams.create(paymentMethodId, clientSecret, returnURL);
    }

    ArgCheck.nonNull(csip);
    csip.withShouldUseStripeSdk(true);
    return csip;
  }

  private ConfirmPaymentIntentParams extractConfirmPaymentIntentParams(final ReadableMap options) {
    ConfirmPaymentIntentParams cpip = null;
    String clientSecret = options.getString("clientSecret");

    ReadableMap paymentMethod = getMapOrNull(options, "paymentMethod");
    String paymentMethodId = getStringOrNull(options,"paymentMethodId");

    // ReadableMap source = options.getMap("source");
    // String sourceId = getStringOrNull(options,"sourceId");

    String returnURL = getStringOrNull(options, "returnURL");
    if (returnURL ==  null) {
      returnURL = "stripejs://use_stripe_sdk/return_url";
    }
    boolean savePaymentMethod = getBooleanOrNull(options, "savePaymentMethod", false);

    // TODO support extra params in each of the create methods below
    Map<String, Object> extraParams = null;

    // Create with Payment Method
    if (paymentMethod != null) {

      PaymentMethodCreateParams pmcp = extractPaymentMethodCreateParams(paymentMethod);
      cpip = ConfirmPaymentIntentParams.createWithPaymentMethodCreateParams(pmcp, clientSecret, returnURL, savePaymentMethod, extraParams);

    // Create with Payment Method ID
    } else if (paymentMethodId != null) {

      cpip = ConfirmPaymentIntentParams.createWithPaymentMethodId(paymentMethodId, clientSecret, returnURL, savePaymentMethod, extraParams);

    // Create with Source
    /**
    Support for creating a Source while confirming a PaymentIntent is not being included
    at this time, however, for compatibility with existing saved Sources, you can still confirm
    a payment intent using a pre-existing Source by specifying its 'sourceId', as shown in the next
    branch
    */
    /*
    } else if (source != null) {
      SourceParams sourceParams = extractSourceParams(source);
      cpip = ConfirmPaymentIntentParams.createWithSourceParams(sourceParams, clientSecret, returnURL, savePaymentMethod, extraParams);
    */

    // Create with Source ID
    /**
    If you have a sourceId, pass it into the paymentMethodId parameter instead!
    The payment_method parameter of a payment intent is fully compatible with Sources.
    Reference: https://stripe.com/docs/api/payment_intents/confirm#confirm_payment_intent-payment_method
    */
    /*
    } else if (sourceId != null) {
      cpip = ConfirmPaymentIntentParams.createWithSourceId(sourceId, clientSecret, returnURL, savePaymentMethod, extraParams);
    */

    /**
    This branch can be used if the client secret refers to a payment intent that already
    has payment method information and just needs to be confirmed.
    */
    } else {
      cpip = ConfirmPaymentIntentParams.create(clientSecret, returnURL);
    }

    cpip.withShouldUseStripeSdk(true);

    return cpip;
  }

  private PaymentMethodCreateParams extractPaymentMethodCreateParams(final ReadableMap options) {

    ReadableMap cardParams = getMapOrNull(options, "card");
    ReadableMap billingDetailsParams = getMapOrNull(options, "billingDetails");
    ReadableMap metadataParams = getMapOrNull(options, "metadata");

    PaymentMethodCreateParams.Card card = null;
    PaymentMethod.BillingDetails billingDetails = null;
    Address address = null;
    Map<String, String> metadata = new HashMap<>();

    if (metadataParams != null) {
      for (String key : metadataParams.keySet()) {
        metadata.put(key, metadataParams.getString(key));
      }
    }

    if (billingDetailsParams != null) {

      ReadableMap addressParams = getMapOrNull(options, "address");

      if (addressParams != null) {
        address = new Address.Builder().
          setCity(getStringOrNull(addressParams, "city")).
          setCountry(addressParams.getString("country")).
          setLine1(getStringOrNull(addressParams, "line1")).
          setLine2(getStringOrNull(addressParams, "line2")).
          setPostalCode(getStringOrNull(addressParams, "postalCode")).
          setState(getStringOrNull(addressParams, "state")).
          build();
      }

      billingDetails = new PaymentMethod.BillingDetails.Builder().
        setAddress(address).
        setEmail(getStringOrNull(billingDetailsParams, "email")).
        setName(getStringOrNull(billingDetailsParams,"name")).
        setPhone(getStringOrNull(billingDetailsParams,"phone")).
        build();
    }

    if (cardParams != null) {
      String token = getStringOrNull(cardParams, "token");
      if (token != null) {
        card = PaymentMethodCreateParams.Card.create(token);
      } else {
        card = new PaymentMethodCreateParams.Card.Builder().
          setCvc(cardParams.getString("cvc")).
          setExpiryMonth(cardParams.getInt("expMonth")).
          setExpiryYear(cardParams.getInt("expYear")).
          setNumber(cardParams.getString("number")).
          build();
      }
    }

    return PaymentMethodCreateParams.create(
      card,
      billingDetails,
      metadata
    );
  }

  private SourceParams extractSourceParams(final ReadableMap options) {
    String sourceType = options.getString("type");
    SourceParams sourceParams = null;
    switch (sourceType) {
      case "alipay":
        sourceParams = SourceParams.createAlipaySingleUseParams(
          options.getInt("amount"),
          options.getString("currency"),
          getStringOrNull(options, "name"),
          getStringOrNull(options, "email"),
          options.getString("returnURL"));
        break;
      case "bancontact":
        sourceParams = SourceParams.createBancontactParams(
          options.getInt("amount"),
          options.getString("name"),
          options.getString("returnURL"),
          getStringOrNull(options, "statementDescriptor"),
          options.getString("preferredLanguage"));
        break;
      case "giropay":
        sourceParams = SourceParams.createGiropayParams(
          options.getInt("amount"),
          options.getString("name"),
          options.getString("returnURL"),
          getStringOrNull(options, "statementDescriptor"));
        break;
      case "ideal":
        sourceParams = SourceParams.createIdealParams(
          options.getInt("amount"),
          options.getString("name"),
          options.getString("returnURL"),
          getStringOrNull(options, "statementDescriptor"),
          getStringOrNull(options, "bank"));
        break;
      case "sepaDebit":
        sourceParams = SourceParams.createSepaDebitParams(
          options.getString("name"),
          options.getString("iban"),
          getStringOrNull(options, "addressLine1"),
          options.getString("city"),
          options.getString("postalCode"),
          options.getString("country"));
        break;
      case "sofort":
        sourceParams = SourceParams.createSofortParams(
          options.getInt("amount"),
          options.getString("returnURL"),
          options.getString("country"),
          getStringOrNull(options, "statementDescriptor"));
        break;
      case "threeDSecure":
        sourceParams = SourceParams.createThreeDSecureParams(
          options.getInt("amount"),
          options.getString("currency"),
          options.getString("returnURL"),
          options.getString("card"));
        break;
      case "card":
        sourceParams = SourceParams.createCardParams(Converters.createCard(options));
        break;
    }
    return sourceParams;
  }


  void processRedirect(@Nullable Uri redirectData) {
    if (mCreatedSource == null || mCreateSourcePromise == null) {

      return;
    }

    if (redirectData == null) {

      mCreateSourcePromise.reject(
        getErrorCode(mErrorCodes, "redirectCancelled"),
        getDescription(mErrorCodes, "redirectCancelled")
      );
      mCreatedSource = null;
      mCreateSourcePromise = null;
      return;
    }

    final String clientSecret = redirectData.getQueryParameter("client_secret");
    if (!mCreatedSource.getClientSecret().equals(clientSecret)) {
      mCreateSourcePromise.reject(
        getErrorCode(mErrorCodes, "redirectNoSource"),
        getDescription(mErrorCodes, "redirectNoSource")
      );
      mCreatedSource = null;
      mCreateSourcePromise = null;
      return;
    }

    final String sourceId = redirectData.getQueryParameter("source");
    if (!mCreatedSource.getId().equals(sourceId)) {
      mCreateSourcePromise.reject(
        getErrorCode(mErrorCodes, "redirectWrongSourceId"),
        getDescription(mErrorCodes, "redirectWrongSourceId")
      );
      mCreatedSource = null;
      mCreateSourcePromise = null;
      return;
    }

    final Promise promise = mCreateSourcePromise;

    // Nulls those properties to avoid processing them twice
    mCreatedSource = null;
    mCreateSourcePromise = null;

    new AsyncTask<Void, Void, Source>() {
      @Override
      protected Source doInBackground(Void... voids) {
        try {
          return mStripe.retrieveSourceSynchronous(sourceId, clientSecret);
        } catch (Exception e) {
          return null;
        }
      }

      @Override
      protected void onPostExecute(Source source) {
        if (source != null) {
          switch (source.getStatus()) {
            case Chargeable:
            case Consumed:
              promise.resolve(convertSourceToWritableMap(source));
              break;
            case Canceled:
              promise.reject(
                      getErrorCode(mErrorCodes, "redirectCancelled"),
                      getDescription(mErrorCodes, "redirectCancelled")
              );
              break;
            case Pending:
            case Failed:
            default:
              promise.reject(
                      getErrorCode(mErrorCodes, "redirectFailed"),
                      getDescription(mErrorCodes, "redirectFailed")
              );
          }
        }
      }
    }.execute();
  }

}
