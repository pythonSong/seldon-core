/*******************************************************************************
 * Copyright 2017 Seldon Technologies Ltd (http://www.seldon.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package io.seldon.engine.service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.seldon.engine.config.AnnotationsConfig;
import io.seldon.engine.exception.APIException;
import io.seldon.engine.grpc.SeldonGrpcServer;
import io.seldon.engine.pb.ProtoBufUtils;
import io.seldon.engine.predictors.PredictiveUnitState;
import io.seldon.protos.CombinerGrpc;
import io.seldon.protos.CombinerGrpc.CombinerBlockingStub;
import io.seldon.protos.DeploymentProtos.Endpoint;
import io.seldon.protos.DeploymentProtos.PredictiveUnit.PredictiveUnitType;
import io.seldon.protos.GenericGrpc;
import io.seldon.protos.GenericGrpc.GenericBlockingStub;
import io.seldon.protos.ModelGrpc;
import io.seldon.protos.ModelGrpc.ModelBlockingStub;
import io.seldon.protos.OutputTransformerGrpc;
import io.seldon.protos.OutputTransformerGrpc.OutputTransformerBlockingStub;
import io.seldon.protos.PredictionProtos.Feedback;
import io.seldon.protos.PredictionProtos.SeldonMessage;
import io.seldon.protos.PredictionProtos.SeldonMessage.DataOneofCase;
import io.seldon.protos.PredictionProtos.SeldonMessageList;
import io.seldon.protos.RouterGrpc;
import io.seldon.protos.RouterGrpc.RouterBlockingStub;
import io.seldon.protos.TransformerGrpc;
import io.seldon.protos.TransformerGrpc.TransformerBlockingStub;

@Service
public class InternalPredictionService {
	
	private static Logger logger = LoggerFactory.getLogger(InternalPredictionService.class.getName());

	public static final String MODEL_NAME_HEADER = "Seldon-model-name"; 
	public static final String MODEL_IMAGE_HEADER = "Seldon-model-image"; 
	public static final String MODEL_VERSION_HEADER = "Seldon-model-version";
	
    public final static String ANNOTATION_REST_CONNECTION_TIMEOUT = "seldon.io/rest-connection-timeout";
    public final static String ANNOTATION_REST_READ_TIMEOUT = "seldon.io/rest-read-timeout";
    public final static String ANNOTATION_GRPC_READ_TIMEOUT = "seldon.io/grpc-read-timeout";

	private static final int DEFAULT_CONNECTION_TIMEOUT = 5000;
	private static final int DEFAULT_READ_TIMEOUT = 10000;
	
	public static final int DEFAULT_GRPC_READ_TIMEOUT = 5000;
	
    ObjectMapper mapper = new ObjectMapper();
    
    RestTemplate restTemplate;
        
    private int grpcMaxMessageSize = io.grpc.internal.GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;
    private int grpcReadTimeout = DEFAULT_GRPC_READ_TIMEOUT;
    
    @Autowired
    public InternalPredictionService(RestTemplateBuilder restTemplateBuilder,AnnotationsConfig annotations){
    	int connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
    	if (annotations.has(ANNOTATION_REST_CONNECTION_TIMEOUT))
    	{
    		try
    		{
    			logger.info("Setting REST connection timeout from annotation {}",ANNOTATION_REST_CONNECTION_TIMEOUT);
    			connectionTimeout = Integer.parseInt(annotations.get(ANNOTATION_REST_CONNECTION_TIMEOUT));
    		}
    		catch(NumberFormatException e)
    		{
    			logger.error("Failed to parse REST connection timeout annotation {} with value {}",ANNOTATION_REST_CONNECTION_TIMEOUT,annotations.get(ANNOTATION_REST_CONNECTION_TIMEOUT));
    		}
    	}
    	logger.info("REST Connection timeout set to {}",connectionTimeout);
    	int readTimeout = DEFAULT_READ_TIMEOUT;
    	if (annotations.has(ANNOTATION_REST_READ_TIMEOUT))
    	{
    		try
    		{
    			logger.info("Setting REST read timeout from annotation {}",ANNOTATION_REST_READ_TIMEOUT);
    			readTimeout = Integer.parseInt(annotations.get(ANNOTATION_REST_READ_TIMEOUT));
    		}
    		catch(NumberFormatException e)
    		{
    			logger.error("Failed to parse REST read timeout annotation {} with value {}",ANNOTATION_REST_READ_TIMEOUT,annotations.get(ANNOTATION_REST_READ_TIMEOUT));
    		}
    	}
    	logger.info("REST read timeout set to {}",readTimeout);
    	this.restTemplate = restTemplateBuilder
    	           .setConnectTimeout(connectionTimeout)
    	           .setReadTimeout(readTimeout)
    	           .build();
    	if (annotations.has(SeldonGrpcServer.ANNOTATION_MAX_MESSAGE_SIZE))
        {
        	try 
        	{
        		grpcMaxMessageSize =Integer.parseInt(annotations.get(SeldonGrpcServer.ANNOTATION_MAX_MESSAGE_SIZE));
        		logger.info("Setting max message to {} bytes",grpcMaxMessageSize);
        	}
        	catch(NumberFormatException e)
        	{
        		logger.error("Failed to parse {} with value {}",SeldonGrpcServer.ANNOTATION_MAX_MESSAGE_SIZE,annotations.get(SeldonGrpcServer.ANNOTATION_MAX_MESSAGE_SIZE),e);
        	}
        }
    	logger.info("gRPC max message size set to {}",grpcMaxMessageSize);
    	if (annotations.has(ANNOTATION_GRPC_READ_TIMEOUT))
        {
        	try 
        	{
        		grpcReadTimeout = Integer.parseInt(annotations.get(ANNOTATION_GRPC_READ_TIMEOUT));
        		logger.info("Setting grpc read timeout to {}ms",grpcReadTimeout);
        	}
        	catch(NumberFormatException e)
        	{
        		logger.error("Failed to parse {} with value {}",ANNOTATION_GRPC_READ_TIMEOUT,annotations.get(ANNOTATION_GRPC_READ_TIMEOUT),e);
        	}
        }
    	logger.info("gRPC read timeout set to {}",grpcReadTimeout);
    }
    
    public SeldonMessage route(SeldonMessage input, PredictiveUnitState state) throws InvalidProtocolBufferException
    {
    	final Endpoint endpoint = state.endpoint;
		switch (endpoint.getType()){
			case REST:
				String dataString = ProtoBufUtils.toJson(input);
				return queryREST("route", dataString, state, endpoint, isDefaultData(input));
				
			case GRPC:
				if (state.type==PredictiveUnitType.UNKNOWN_TYPE){
					GenericBlockingStub stub =  GenericGrpc.newBlockingStub(getChannel(endpoint))
							.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
							.withMaxInboundMessageSize(grpcMaxMessageSize)
							.withMaxOutboundMessageSize(grpcMaxMessageSize);
					return stub.route(input);
				}
				else {
					RouterBlockingStub stub =  RouterGrpc.newBlockingStub(getChannel(endpoint))
							.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
							.withMaxInboundMessageSize(grpcMaxMessageSize)
							.withMaxOutboundMessageSize(grpcMaxMessageSize);
					return stub.route(input);
				}
		}
		throw new APIException(APIException.ApiExceptionType.ENGINE_MICROSERVICE_ERROR,"no service available");
    }
    
    public SeldonMessage sendFeedback(Feedback feedback, PredictiveUnitState state) throws InvalidProtocolBufferException
    {
    	final Endpoint endpoint = state.endpoint;
		switch (endpoint.getType()){
			case REST:
				String dataString = ProtoBufUtils.toJson(feedback);
				return queryREST("send-feedback", dataString, state, endpoint, true);
				
			case GRPC:
				if (state.type==PredictiveUnitType.UNKNOWN_TYPE){
					GenericBlockingStub stub =  GenericGrpc.newBlockingStub(getChannel(endpoint))
							.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
							.withMaxInboundMessageSize(grpcMaxMessageSize)
							.withMaxOutboundMessageSize(grpcMaxMessageSize);
					return stub.sendFeedback(feedback);
				}
				else if (state.type == PredictiveUnitType.MODEL)
				{
					ModelBlockingStub modelStub = ModelGrpc.newBlockingStub(getChannel(endpoint))
							.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
							.withMaxInboundMessageSize(grpcMaxMessageSize)
							.withMaxOutboundMessageSize(grpcMaxMessageSize);
						return modelStub.sendFeedback(feedback);
				}
				else {
					RouterBlockingStub routerStub =  RouterGrpc.newBlockingStub(getChannel(endpoint))
							.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
							.withMaxInboundMessageSize(grpcMaxMessageSize)
							.withMaxOutboundMessageSize(grpcMaxMessageSize);
					return routerStub.sendFeedback(feedback);
				}
		}
		throw new APIException(APIException.ApiExceptionType.ENGINE_MICROSERVICE_ERROR,"no service available");
    }
    
    public SeldonMessage transformInput(SeldonMessage input, PredictiveUnitState state) throws InvalidProtocolBufferException
    {
    	final Endpoint endpoint = state.endpoint;
		switch (endpoint.getType()){
			case REST:
				String dataString = ProtoBufUtils.toJson(input);
				if (state.type == PredictiveUnitType.MODEL) {
					return queryREST("predict", dataString, state, endpoint, isDefaultData(input));
				}
				else {
					return queryREST("transform-input", dataString, state, endpoint, isDefaultData(input));
				}
				
			case GRPC:
				switch (state.type){
					case UNKNOWN_TYPE:
						GenericBlockingStub genStub = GenericGrpc.newBlockingStub(getChannel(endpoint))
							.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
							.withMaxInboundMessageSize(grpcMaxMessageSize)
							.withMaxOutboundMessageSize(grpcMaxMessageSize);
						return genStub.transformInput(input);
					case MODEL:
						ModelBlockingStub modelStub = ModelGrpc.newBlockingStub(getChannel(endpoint))
							.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
							.withMaxInboundMessageSize(grpcMaxMessageSize)
							.withMaxOutboundMessageSize(grpcMaxMessageSize);
						return modelStub.predict(input);
					case TRANSFORMER:
						TransformerBlockingStub transformerStub = TransformerGrpc.newBlockingStub(getChannel(endpoint))
						.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
						.withMaxInboundMessageSize(grpcMaxMessageSize)
						.withMaxOutboundMessageSize(grpcMaxMessageSize);
						return transformerStub.transformInput(input);
					default:
						throw new APIException(APIException.ApiExceptionType.ENGINE_MICROSERVICE_ERROR,"Unhandled type");
				}
		}
		throw new APIException(APIException.ApiExceptionType.ENGINE_MICROSERVICE_ERROR,"no service available");
    }
    
    public SeldonMessage transformOutput(SeldonMessage output, PredictiveUnitState state) throws InvalidProtocolBufferException
    {
    	final Endpoint endpoint = state.endpoint;
		switch (endpoint.getType()){
			case REST:
				String dataString = ProtoBufUtils.toJson(output);
				return queryREST("transform-output", dataString, state, endpoint, isDefaultData(output));
				
			case GRPC:
				if (state.type==PredictiveUnitType.UNKNOWN_TYPE){
					GenericBlockingStub stub =  GenericGrpc.newBlockingStub(getChannel(endpoint))
							.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
							.withMaxInboundMessageSize(grpcMaxMessageSize)
							.withMaxOutboundMessageSize(grpcMaxMessageSize);
					return stub.transformOutput(output);
				}
				else {
					OutputTransformerBlockingStub stub =  OutputTransformerGrpc.newBlockingStub(getChannel(endpoint))
							.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
							.withMaxInboundMessageSize(grpcMaxMessageSize)
							.withMaxOutboundMessageSize(grpcMaxMessageSize);
					return stub.transformOutput(output);
				}
		}
		throw new APIException(APIException.ApiExceptionType.ENGINE_MICROSERVICE_ERROR,"no service available");
    }
    
    public SeldonMessage aggregate(List<SeldonMessage> outputs, PredictiveUnitState state) throws InvalidProtocolBufferException{
    	final Endpoint endpoint = state.endpoint;
    	SeldonMessageList outputsList = SeldonMessageList.newBuilder().addAllSeldonMessages(outputs).build();
		switch (endpoint.getType()){
			case REST:
				String dataString = ProtoBufUtils.toJson(outputsList);
				return queryREST("aggregate", dataString, state, endpoint, true);
				
			case GRPC:
				if (state.type==PredictiveUnitType.UNKNOWN_TYPE){
					GenericBlockingStub stub =  GenericGrpc.newBlockingStub(getChannel(endpoint))
							.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
							.withMaxInboundMessageSize(grpcMaxMessageSize)
							.withMaxOutboundMessageSize(grpcMaxMessageSize);
					return stub.aggregate(outputsList);
				}
				else {
					CombinerBlockingStub stub = CombinerGrpc.newBlockingStub(getChannel(endpoint))
							.withDeadlineAfter(grpcReadTimeout, TimeUnit.MILLISECONDS)
							.withMaxInboundMessageSize(grpcMaxMessageSize)
							.withMaxOutboundMessageSize(grpcMaxMessageSize);
					return stub.aggregate(outputsList);
				}
		}
		throw new APIException(APIException.ApiExceptionType.ENGINE_MICROSERVICE_ERROR,"no service available");
    }
		
    private boolean isDefaultData(SeldonMessage message){
    	if (message.getDataOneofCase() == DataOneofCase.DATA)
			return true;
    	return false;
    }
    
	private ManagedChannel getChannel(Endpoint endpoint){
		ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getServiceHost(), endpoint.getServicePort()).usePlaintext(true).build();
		return channel;
	}
	
	private SeldonMessage queryREST(String path, String dataString, PredictiveUnitState state, Endpoint endpoint, boolean isDefault)
	{
		long timeNow = System.currentTimeMillis();
		URI uri;
		try {
			URIBuilder builder = new URIBuilder().setScheme("http")
					.setHost(endpoint.getServiceHost())
					.setPort(endpoint.getServicePort())
					.setPath("/"+path);

			uri = builder.build();
		} catch (URISyntaxException e) 
		{
			throw new APIException(APIException.ApiExceptionType.ENGINE_INVALID_ENDPOINT_URL,"Host: "+endpoint.getServiceHost()+" port:"+endpoint.getServicePort());
		}
		
		try  
		{
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
			headers.add(MODEL_NAME_HEADER, state.name);
			headers.add(MODEL_IMAGE_HEADER, state.imageName);
			headers.add(MODEL_VERSION_HEADER, state.imageVersion);
			
			MultiValueMap<String, String> map= new LinkedMultiValueMap<String, String>();
			map.add("json", dataString);
			map.add("isDefault", Boolean.toString(isDefault));

			HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<MultiValueMap<String, String>>(map, headers);

			logger.info("Requesting " + uri.toString());
			ResponseEntity<String> httpResponse = restTemplate.postForEntity( uri, request , String.class );
			
			try
			{
				if(httpResponse.getStatusCode().is2xxSuccessful()) 
				{
				    SeldonMessage.Builder builder = SeldonMessage.newBuilder();
				    String response = httpResponse.getBody();
				    logger.info(response);
				    JsonFormat.parser().ignoringUnknownFields().merge(response, builder);
				    return builder.build();
				} 
				else 
				{
					logger.error("Couldn't retrieve prediction from external prediction server -- bad http return code: " + httpResponse.getStatusCode());
					throw new APIException(APIException.ApiExceptionType.ENGINE_MICROSERVICE_ERROR,String.format("Bad return code %d", httpResponse.getStatusCode()));
				}
			}
			finally
			{
				if (logger.isDebugEnabled())
					logger.debug("External prediction server took "+(System.currentTimeMillis()-timeNow) + "ms");
			}
		} 
		catch (IOException e) 
		{
			logger.error("Couldn't retrieve prediction from external prediction server - ", e);
			throw new APIException(APIException.ApiExceptionType.ENGINE_MICROSERVICE_ERROR,e.toString());
		}
		catch (Exception e)
        {
			logger.error("Couldn't retrieve prediction from external prediction server - ", e);
			throw new APIException(APIException.ApiExceptionType.ENGINE_MICROSERVICE_ERROR,e.toString());
        }
		finally
		{
			
		}
	}

	 /**
     * Used only for testing. Should be replaced by better methods that use Spring and Mockito to create a Mock RestTemplate for testing
     * @param predictorSpec
     */
	public void setRestTemplate(RestTemplate restTemplate) { // FIXME
		this.restTemplate = restTemplate;
	}

}
