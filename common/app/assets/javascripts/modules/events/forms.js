/* global stripe */
define([
    'stripePayment',
    'credentials',
    'externalDependencies',
    'user',
    'require'
], function(stripePayment, credentials, externalDependencies, userUtil){
    'use strict';

    var stripeResponseHandler = function (status, response) {
        var $form = jQuery('#payment-form');

        if (response.error) {
            // Show the errors on the form
            $form.find('.payment-errors').text(response.error.message);
            $form.find('button').prop('disabled', false);
        } else {
            // token contains id, last4, and card type
            var token = response.id;
            // Insert the token into the form so it gets submitted to the server
            $form.append(jQuery('<input type="hidden" name="stripeToken" />').val(token));
            // and submit
            $form.get(0).submit();
        }
    };

    var bindFormatting = function ($form) {
        $form.find('.cc-num').payment('formatCardNumber');
        $form.find('.cc-cvc').payment('formatCardCVC');
        $form.find('.cc-exp-month').payment('restrictNumeric');
        $form.find('.cc-exp-year').payment('restrictNumeric');

    };

    var validateForm = function ($form) {

        var number = $form.find('.cc-num');
        var cvc = $form.find('.cc-cvc');
        var expiryMonth = $form.find('.cc-exp-month');
        var expiryYear = $form.find('.cc-exp-year');

        $form.find('.invalid').removeClass('invalid');

        if (!jQuery.payment.validateCardNumber(number.val())) {
            number.addClass('invalid');
            return false;
        }

        if (!jQuery.payment.validateCardCVC(cvc.val(), jQuery.payment.cardType(number.val()))) {
            cvc.addClass('invalid');
            return false;
        }

        if (!$.payment.validateCardExpiry(expiryMonth.val(), expiryYear.val())) {
            expiryMonth.addClass('invalid');
            expiryYear.addClass('invalid');
            return false;
        }

        return true;
    };

    var populateUserInformation = function () {
        var user = userUtil.getUserFromCookie();

        if (user) {
            var str = [];
            str.push('User from cookie - ');
            str.push('\r\ndisplayname: ' + user.displayname);
            str.push('\r\nid: ' + user.id);
            str.push('\r\nemail: ' + user.primaryemailaddress);

            jQuery('p.user').append(str.join(''));
        } else {
            throw new Error('no guUser cookie could be found on this domain :-(');
        }

    };

    var init = function () {

        populateUserInformation();

        var $form = jQuery('#payment-form');

        bindFormatting($form);

        stripe.setPublishableKey(credentials.stripe.stripePublishableKey);

        $form.submit(function(event) {
            event.preventDefault();

            // Disable the submit button to prevent repeated clicks
            $form.find('button').prop('disabled', true);
            if (validateForm($form)) {
                stripe.card.createToken($form, stripeResponseHandler);
            } else {
                $form.find('button').prop('disabled', false);
            }

        });
    };

    return {
        init: init
    };

});