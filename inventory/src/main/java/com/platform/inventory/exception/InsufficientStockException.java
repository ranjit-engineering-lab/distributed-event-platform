package com.platform.inventory.exception;



public class InsufficientStockException extends RuntimeException {
    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	final String productId; final int requested; final int available;
    
	public InsufficientStockException(String productId, int requested, int available) {
        super(String.format("Insufficient stock for %s: requested=%d, available=%d", productId, requested, available));
        this.productId = productId; this.requested = requested; this.available = available;
    }
}