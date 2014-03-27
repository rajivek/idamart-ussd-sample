/*
 *   (C) Copyright 1996-2012 hSenid Software International (Pvt) Limited.
 *   All Rights Reserved.
 *
 *   These materials are unpublished, proprietary, confidential source code of
 *   hSenid Software International (Pvt) Limited and constitute a TRADE SECRET
 *   of hSenid Software International (Pvt) Limited.
 *
 *   hSenid Software International (Pvt) Limited retains all title to and intellectual
 *   property rights in these materials.
 */
package com.ideamart.sample.ussd.client;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import hms.kite.samples.api.SdpException;
import hms.kite.samples.api.StatusCodes;
import hms.kite.samples.api.ussd.MoUssdListener;
import hms.kite.samples.api.ussd.UssdRequestSender;
import hms.kite.samples.api.ussd.messages.MoUssdReq;
import hms.kite.samples.api.ussd.messages.MtUssdReq;
import hms.kite.samples.api.ussd.messages.MtUssdResp;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.MissingResourceException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ideamart.sample.ussd.util.Messages;

public class MainMenu implements MoUssdListener {

    private final static Logger LOGGER = Logger.getLogger(MainMenu.class.getName());

    // hardcoded values
    private static final String EXIT_SERVICE_CODE = "000";
    private static final String BACK_SERVICE_CODE = "999";
    private static final String PROPERTY_KEY_PREFIX = "menu.level.";
    private static final String USSD_OP_MO_INIT = "mo-init";
    private static final String USSD_OP_MT_CONT = "mt-cont";
    private static final String USSD_OP_MT_FIN = "mt-fin";

    // menu state saving for back button
    private List<Byte> menuState;
    // service to send the request
    private UssdRequestSender ussdMtSender;

    // creating a cashe loader to keep menustate referance according to the session id
    private CacheLoader<String, List<Byte>> loader = new CacheLoader<String, List<Byte>>() {
        @Override
        public List<Byte> load(final String key) throws Exception {
            return createExpensiveGraph(key);
        }

    };

    private List<Byte> createExpensiveGraph(final String key) {
        return new ArrayList<Byte>();
    }

    // cashe build the cashloader this will remove the element after 2minutes from the last access.
    //this use to keep menustate according to the session id upto some period, after that it will remove the menustate.
    private LoadingCache<String, List<Byte>> cache = CacheBuilder.newBuilder().expireAfterAccess(2, TimeUnit.MINUTES)
            .build(loader);

    @Override
    public void init() {
        // create and initialize service
        try {
            ussdMtSender = new UssdRequestSender(new URL(Messages.getMessage("sdp.server.url")));
        } catch (MalformedURLException e) {
            LOGGER.log(Level.INFO, "Unexpected error occurred", e);
        }
    }

    /**
     * Receive requests
     *
     * @param moUssdReq
     */
    @Override
    public void onReceivedUssd(final MoUssdReq moUssdReq) {
        try {
            menuState = cache.get(moUssdReq.getSessionId());
            // start processing request
            processRequest(moUssdReq);
        } catch (SdpException e) {
            LOGGER.log(Level.INFO, "Unexpected error occurred", e);
        } catch (ExecutionException e) {
            LOGGER.log(Level.INFO, "Unexpected error occurred", e);
        }
    }

    /**
     * Build the response based on the requested service code
     *
     * @param moUssdReq
     */
    private void processRequest(final MoUssdReq moUssdReq) throws SdpException {

        // exit request - session destroy
        if (moUssdReq.getMessage().equals(EXIT_SERVICE_CODE)) {
            terminateSession(moUssdReq);
            return;// completed work and return
        }

        // back button handling
        if (moUssdReq.getMessage().equals(BACK_SERVICE_CODE)) {
            backButtonHandle(moUssdReq);
            return;// completed work and return
        }

        // get current service code
        byte serviceCode;
        if (USSD_OP_MO_INIT.equals(moUssdReq.getUssdOperation())) {
            serviceCode = 0;
            clearMenuState();
        } else {
            serviceCode = getServiceCode(moUssdReq);
        }
        // create request to display user
        final MtUssdReq request = createRequest(moUssdReq, buildMenuContent(serviceCode), USSD_OP_MT_CONT);
        sendRequest(request);
        // record menu state
        menuState.add(serviceCode);
    }

    /**
     * Build request object
     *
     * @param moUssdReq     - Receive request object
     * @param menuContent   - menu to display next
     * @param ussdOperation - operation
     * @return MtUssdReq - filled request object
     */
    private MtUssdReq createRequest(final MoUssdReq moUssdReq, final String menuContent, final String ussdOperation) {
        final MtUssdReq request = new MtUssdReq();
        request.setApplicationId(moUssdReq.getApplicationId());
        request.setEncoding(moUssdReq.getEncoding());
        request.setMessage(menuContent);
        request.setPassword(Messages.getMessage(moUssdReq.getApplicationId()));
        request.setSessionId(moUssdReq.getSessionId());
        request.setUssdOperation(Messages.getMessage("operation"));
        request.setVersion(moUssdReq.getVersion());
        request.setDestinationAddress(moUssdReq.getSourceAddress());
        return request;
    }

    /**
     * load a property from ussdmenu.properties
     *
     * @param key
     * @return value
     */
    private String getText(final byte key) {
        return getPropertyValue(PROPERTY_KEY_PREFIX + key);
    }

  private String getPropertyValue(final String key)
    {
    	String message = Messages.getMessage(key);
    	if (message == null)
    		return BACK_SERVICE_CODE;
        return message;
    }

    /**
     * Request sender
     *
     * @param request
     * @return MtUssdResp
     */
    private MtUssdResp sendRequest(final MtUssdReq request) throws SdpException {
        // sending request to service
        MtUssdResp response = null;
        try {
            response = ussdMtSender.sendUssdRequest(request);
        } catch (SdpException e) {
            LOGGER.log(Level.INFO, "Unable to send request", e);
            throw e;
        }

        // response status
        String statusCode = response.getStatusCode();
        String statusDetails = response.getStatusDetail();
        if (StatusCodes.SuccessK.equals(statusCode)) {
            LOGGER.info("MT USSD message successfully sent");
        } else {
            LOGGER.info("MT USSD message sending failed with status code [" + statusCode + "] " + statusDetails);
        }
        return response;
    }

    /**
     * Clear history list
     */
    private void clearMenuState() {
        LOGGER.info("clear history List");
        menuState.clear();
    }

    /**
     * Terminate session
     *
     * @param moUssdReq
     * @throws SdpException
     */
    private void terminateSession(final MoUssdReq moUssdReq) throws SdpException {
        final MtUssdReq request = createRequest(moUssdReq, "", USSD_OP_MT_FIN);
        sendRequest(request);
    }

    /**
     * Handlling back button with menu state
     *
     * @param moUssdReq
     * @throws SdpException
     */
    private void backButtonHandle(final MoUssdReq moUssdReq) throws SdpException {
        byte lastMenuVisited = 0;

        // remove last menu when back
        if (menuState.size() > 0) {
            menuState.remove(menuState.size() - 1);
            lastMenuVisited = menuState.get(menuState.size() - 1);
        }

        // create request and send
        final MtUssdReq request = createRequest(moUssdReq, buildMenuContent(lastMenuVisited), USSD_OP_MT_CONT);
        sendRequest(request);

        // clear menu status
        if (lastMenuVisited == 0) {
            clearMenuState();
            // add 0 to menu state ,finally its in main menu
            menuState.add((byte) 0);
        }

    }

    /**
     * Create service code to navigate through menu and for property loading
     *
     * @param moUssdReq
     * @return serviceCode
     */
    private byte getServiceCode(final MoUssdReq moUssdReq) {
        byte serviceCode = 0;
        try {
            serviceCode = Byte.parseByte(moUssdReq.getMessage());
        } catch (NumberFormatException e) {
            return serviceCode;
        }

        // create service codes for child menus based on the main menu codes
        if (menuState.size() > 0 && menuState.get(menuState.size() - 1) != 0) {
            String generatedChildServiceCode = "" + menuState.get(menuState.size() - 1) + serviceCode;
            serviceCode = Byte.parseByte(generatedChildServiceCode);
        }

        return serviceCode;
    }

    /**
     * Build menu based on the service code
     *
     * @param selection
     * @return menuContent
     */
    private String buildMenuContent(final byte selection) {
        String menuContent;
        try {
            // build menu contents
            menuContent = getText(selection);
        } catch (MissingResourceException e) {
            // back to main menu
            menuContent = getText((byte) 0);
        }
        return menuContent;
    }

}
