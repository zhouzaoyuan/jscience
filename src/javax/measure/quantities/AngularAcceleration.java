/*
 * JScience - Java(TM) Tools and Libraries for the Advancement of Sciences.
 * Copyright (C) 2006 - JScience (http://jscience.org/)
 * All rights reserved.
 * 
 * Permission to use, copy, modify, and distribute this software is
 * freely granted, provided that this notice is preserved.
 */
package javax.measure.quantities;
import javax.measure.units.ProductUnit;
import javax.measure.units.SI;
import javax.measure.units.Unit;

/**
 * This interface represents the rate of change of angular velocity with respect
 * to time. The system unit for this quantity is "rad/s²" (radian per 
 * square second).
 * 
 * @author  <a href="mailto:jean-marie@dautelle.com">Jean-Marie Dautelle</a>
 * @version 1.0, January 14, 2006
 */
public interface AngularAcceleration extends Quantity<AngularAcceleration> {

    /**
     * Holds the SI unit (Système International d'Unités) for this quantity.
     */
    public final static Unit<AngularAcceleration> UNIT 
        = new ProductUnit<AngularAcceleration>(SI.RADIAN.divide(SI.SECOND.pow(2)));

}