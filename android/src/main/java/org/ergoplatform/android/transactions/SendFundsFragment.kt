package org.ergoplatform.android.transactions

import android.animation.LayoutTransition
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.view.descendants
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.integration.android.IntentIntegrator
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
import org.ergoplatform.*
import org.ergoplatform.android.Preferences
import org.ergoplatform.android.R
import org.ergoplatform.android.databinding.FragmentSendFundsBinding
import org.ergoplatform.android.databinding.FragmentSendFundsTokenItemBinding
import org.ergoplatform.android.ui.*
import org.ergoplatform.android.wallet.addresses.AddressChooserCallback
import org.ergoplatform.android.wallet.addresses.ChooseAddressListDialogFragment
import org.ergoplatform.persistance.WalletConfig
import org.ergoplatform.persistance.WalletToken
import org.ergoplatform.tokens.isSingularToken
import org.ergoplatform.transactions.PromptSigningResult
import org.ergoplatform.utils.formatFiatToString
import org.ergoplatform.wallet.addresses.getAddressLabel
import org.ergoplatform.wallet.getNumOfAddresses


/**
 * Here's the place to send transactions
 */
class SendFundsFragment : AbstractAuthenticationFragment(), PasswordDialogCallback,
    AddressChooserCallback {
    private var _binding: FragmentSendFundsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: SendFundsViewModel
    private val args: SendFundsFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel =
            ViewModelProvider(this).get(SendFundsViewModel::class.java)

        // Inflate the layout for this fragment
        _binding = FragmentSendFundsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.initWallet(
            requireContext(),
            args.walletId,
            args.derivationIdx,
            args.paymentRequest
        )

        // Add observers
        viewModel.walletName.observe(viewLifecycleOwner, {
            // when wallet is loaded, wallet name is set. we can init everything wallet specific here
            binding.walletName.text = getString(R.string.label_send_from, it)
            binding.hintReadonly.visibility =
                if (viewModel.uiLogic.wallet!!.walletConfig.secretStorage == null) View.VISIBLE else View.GONE
            enableLayoutChangeAnimations()
        })
        viewModel.address.observe(viewLifecycleOwner, {
            binding.addressLabel.text =
                it?.getAddressLabel(AndroidStringProvider(requireContext()))
                    ?: getString(
                        R.string.label_all_addresses,
                        viewModel.uiLogic.wallet?.getNumOfAddresses()
                    )
        })
        viewModel.walletBalance.observe(viewLifecycleOwner, {
            binding.tvBalance.text = getString(
                R.string.label_wallet_balance,
                it.toStringRoundToDecimals()
            )
        })
        viewModel.grossAmount.observe(viewLifecycleOwner, {
            binding.tvFee.text = getString(
                R.string.desc_fee,
                viewModel.uiLogic.feeAmount.toStringRoundToDecimals()
            )
            binding.grossAmount.amount = it.toDouble()
            val nodeConnector = NodeConnector.getInstance()
            binding.tvFiat.visibility =
                if (nodeConnector.fiatCurrency.isNotEmpty()) View.VISIBLE else View.GONE
            binding.tvFiat.setText(
                getString(
                    R.string.label_fiat_amount,
                    formatFiatToString(
                        viewModel.uiLogic.amountToSend.toDouble() * nodeConnector.fiatValue.value.toDouble(),
                        nodeConnector.fiatCurrency, AndroidStringProvider(requireContext())
                    ),
                )
            )
        })
        viewModel.tokensChosenLiveData.observe(viewLifecycleOwner, {
            refreshTokensList()
        })
        viewModel.lockInterface.observe(viewLifecycleOwner, {
            if (it)
                ProgressBottomSheetDialogFragment.showProgressDialog(childFragmentManager)
            else
                ProgressBottomSheetDialogFragment.dismissProgressDialog(childFragmentManager)
        })
        viewModel.txWorkDoneLiveData.observe(viewLifecycleOwner, {
            if (!it.success) {
                val snackbar = Snackbar.make(
                    requireView(),
                    if (it is PromptSigningResult)
                        R.string.error_prepare_transaction
                    else R.string.error_send_transaction,
                    Snackbar.LENGTH_LONG
                )
                it.errorMsg?.let { errorMsg ->
                    snackbar.setAction(
                        R.string.label_details
                    ) {
                        showDialogWithCopyOption(requireContext(), errorMsg)
                    }
                }
                snackbar.setAnchorView(R.id.nav_view).show()
            } else if (it is PromptSigningResult) {
                // if this is a prompt signing result, switch to prompt signing dialog
                SigningPromptDialogFragment().show(childFragmentManager, null)
            }
        })
        viewModel.txId.observe(viewLifecycleOwner, {
            it?.let {
                binding.cardviewTxEdit.visibility = View.GONE
                binding.cardviewTxDone.visibility = View.VISIBLE
                binding.labelTxId.text = it
            }
        })

        // Add click listeners
        binding.addressLabel.setOnClickListener {
            viewModel.uiLogic.wallet?.let { wallet ->
                ChooseAddressListDialogFragment.newInstance(
                    wallet.walletConfig.id, true
                ).show(childFragmentManager, null)
            }
        }
        binding.buttonShareTx.setOnClickListener {
            val txUrl = getExplorerTxUrl(binding.labelTxId.text.toString())
            val sendIntent: Intent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, txUrl)
                type = "text/plain"
            }

            val shareIntent = Intent.createChooser(sendIntent, null)
            startActivity(shareIntent)
        }
        binding.buttonDismiss.setOnClickListener {
            val succeeded = findNavController().popBackStack()
            // back stack might be empty when coming from a deep link
            if (!succeeded) {
                findNavController().navigate(R.id.navigation_wallet)
            }
        }

        binding.buttonSend.setOnClickListener {
            startPayment()
        }

        binding.buttonScan.setOnClickListener {
            IntentIntegrator.forSupportFragment(this).initiateScan(setOf(IntentIntegrator.QR_CODE))
        }
        binding.buttonAddToken.setOnClickListener {
            ChooseTokenListDialogFragment().show(childFragmentManager, null)
        }
        binding.amount.setEndIconOnClickListener {
            setAmountEdittext(viewModel.uiLogic.getMaxPossibleAmountToSend())
        }
        binding.hintReadonly.setOnClickListener {
            openUrlWithBrowser(requireContext(), URL_COLD_WALLET_HELP)
        }

        // Init other stuff
        binding.tvReceiver.editText?.setText(viewModel.uiLogic.receiverAddress)
        if (viewModel.uiLogic.amountToSend.nanoErgs > 0) {
            setAmountEdittext(viewModel.uiLogic.amountToSend)
        }

        binding.amount.editText?.addTextChangedListener(MyTextWatcher(binding.amount))
        binding.tvReceiver.editText?.addTextChangedListener(MyTextWatcher(binding.tvReceiver))

        // this triggers an automatic scroll so the amount field is visible when soft keyboard is
        // opened or when amount edittext gets focus
        binding.amount.editText?.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus)
                ensureAmountVisibleDelayed()
        }
        KeyboardVisibilityEvent.setEventListener(
            requireActivity(),
            viewLifecycleOwner,
            { keyboardOpen ->
                if (keyboardOpen && binding.amount.editText?.hasFocus() == true) {
                    ensureAmountVisibleDelayed()
                }
            })
    }

    private fun enableLayoutChangeAnimations() {
        // set layout change animations. they are not set in the xml to avoid animations for the first
        // time the layout is displayed, and enabling them is delayed due to the same reason
        Handler().postDelayed({
            _binding?.let { binding ->
                binding.container.layoutTransition = LayoutTransition()
                binding.container.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)
            }
        }, 200)
    }

    private fun ensureAmountVisibleDelayed() {
        // delay 200 to make sure that smart keyboard is already open
        Handler().postDelayed(
            { _binding?.let { it.scrollView.smoothScrollTo(0, it.amount.top) } },
            200
        )
    }

    override fun onAddressChosen(addressDerivationIdx: Int?) {
        viewModel.uiLogic.derivedAddressIdx = addressDerivationIdx
    }

    private fun refreshTokensList() {
        val tokensAvail = viewModel.uiLogic.tokensAvail
        val tokensChosen = viewModel.uiLogic.tokensChosen

        binding.buttonAddToken.visibility =
            if (tokensAvail.size > tokensChosen.size) View.VISIBLE else View.INVISIBLE
        binding.labelTokenAmountError.visibility = View.GONE
        binding.tokensList.apply {
            this.visibility =
                if (tokensChosen.isNotEmpty()) View.VISIBLE else View.GONE
            this.removeAllViews()
            tokensChosen.forEach {
                val ergoId = it.key
                tokensAvail.firstOrNull { it.tokenId.equals(ergoId) }?.let { tokenDbEntity ->
                    val itemBinding =
                        FragmentSendFundsTokenItemBinding.inflate(layoutInflater, this, true)
                    itemBinding.tvTokenName.text = tokenDbEntity.name

                    val amountChosen = it.value.value
                    val isSingular = tokenDbEntity.isSingularToken() && amountChosen == 1L

                    if (isSingular) {
                        itemBinding.inputTokenAmount.visibility = View.GONE
                        itemBinding.buttonTokenAll.visibility = View.INVISIBLE
                    } else {
                        itemBinding.inputTokenAmount.inputType =
                            if (tokenDbEntity.decimals > 0) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                            else InputType.TYPE_CLASS_NUMBER
                        itemBinding.inputTokenAmount.addTextChangedListener(
                            TokenAmountWatcher(tokenDbEntity, itemBinding)
                        )
                        itemBinding.inputTokenAmount.setText(
                            viewModel.uiLogic.tokenAmountToText(
                                amountChosen,
                                tokenDbEntity.decimals
                            )
                        )
                        itemBinding.buttonTokenAll.setOnClickListener {
                            itemBinding.inputTokenAmount.setText(
                                viewModel.uiLogic.tokenAmountToText(
                                    tokenDbEntity.amount!!,
                                    tokenDbEntity.decimals
                                )
                            )
                            itemBinding.buttonTokenAll.visibility = View.INVISIBLE
                        }
                    }

                    itemBinding.buttonTokenRemove.setOnClickListener {
                        if (isSingular || itemBinding.inputTokenAmount.text.isEmpty()) {
                            viewModel.uiLogic.removeToken(ergoId)
                        } else {
                            itemBinding.inputTokenAmount.text = null
                            itemBinding.inputTokenAmount.requestFocus()
                        }
                    }
                }
            }

            setFocusToEmptyTokenAmountInput()
        }

        showPaymentRequestWarnings()
    }

    private fun setFocusToEmptyTokenAmountInput() {
        binding.tokensList.descendants.firstOrNull {
            it is EditText && it.text.isEmpty() && it.isEnabled && it.visibility == View.VISIBLE
        }?.requestFocus()
    }

    private fun setAmountEdittext(amountToSend: ErgoAmount) {
        binding.amount.editText?.setText(amountToSend.toStringTrimTrailingZeros())
    }

    private fun startPayment() {
        val checkResponse = viewModel.uiLogic.checkCanMakePayment()

        if (checkResponse.receiverError) {
            binding.tvReceiver.error = getString(R.string.error_receiver_address)
            binding.tvReceiver.editText?.requestFocus()
        }
        if (checkResponse.amountError) {
            binding.amount.error = getString(R.string.error_amount)
            if (!checkResponse.receiverError) binding.amount.editText?.requestFocus()
        }
        if (checkResponse.tokenError) {
            binding.labelTokenAmountError.visibility = View.VISIBLE
            setFocusToEmptyTokenAmountInput()
        }

        if (checkResponse.canPay) {
            startAuthFlow(viewModel.uiLogic.wallet!!.walletConfig)
        }
    }

    override fun startAuthFlow(walletConfig: WalletConfig) {
        if (walletConfig.secretStorage == null) {
            // we have a read only wallet here, let's go to cold wallet support mode
            val context = requireContext()
            viewModel.uiLogic.startColdWalletPayment(
                Preferences(context),
                AndroidStringProvider(context)
            )
        } else {
            super.startAuthFlow(walletConfig)
        }
    }

    override fun proceedAuthFlowWithPassword(password: String): Boolean {
        return viewModel.startPaymentWithPassword(password, requireContext())
    }

    override fun showBiometricPrompt() {
        hideForcedSoftKeyboard(requireContext(), binding.amount.editText!!)
        super.showBiometricPrompt()
    }

    override fun proceedAuthFlowFromBiometrics() {
        context?.let { viewModel.startPaymentUserAuth(it) }
    }

    private fun inputChangesToViewModel() {
        viewModel.uiLogic.receiverAddress = binding.tvReceiver.editText?.text?.toString() ?: ""

        val amountStr = binding.amount.editText?.text.toString()
        val ergoAmount = amountStr.toErgoAmount()
        viewModel.uiLogic.amountToSend = ergoAmount ?: ErgoAmount.ZERO
        if (ergoAmount == null) {
            // conversion error, too many decimals or too big for long
            binding.amount.error = getString(R.string.error_amount)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            result.contents?.let {
                viewModel.uiLogic.qrCodeScanned(it, { data, walletId ->
                    findNavController().navigate(
                        SendFundsFragmentDirections
                            .actionSendFundsFragmentToColdWalletSigningFragment(
                                data,
                                walletId
                            )
                    )
                }, { address, amount ->
                    binding.tvReceiver.editText?.setText(address)
                    amount?.let { setAmountEdittext(amount) }
                })
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun showPaymentRequestWarnings() {
        viewModel.uiLogic.getPaymentRequestWarnings(AndroidStringProvider(requireContext()))?.let {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(it)
                .setPositiveButton(R.string.zxing_button_ok, null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class MyTextWatcher(private val textInputLayout: TextInputLayout) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

        }

        override fun afterTextChanged(s: Editable?) {
            textInputLayout.error = null
            inputChangesToViewModel()
        }

    }

    inner class TokenAmountWatcher(
        private val token: WalletToken,
        private val itemBinding: FragmentSendFundsTokenItemBinding
    ) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

        }

        override fun afterTextChanged(s: Editable?) {
            viewModel.uiLogic.setTokenAmount(
                token.tokenId!!,
                s?.toString()?.toTokenAmount(token.decimals) ?: TokenAmount(0, token.decimals)
            )
            binding.labelTokenAmountError.visibility = View.GONE
            itemBinding.buttonTokenAll.visibility = View.VISIBLE
        }

    }
}