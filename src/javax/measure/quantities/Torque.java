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
 * This interface represents the moment of a force. The system unit for this
 * quantity is "N·m" (Newton-Meter).
 * 
 * <p> Note: The Newton-metre ("N·m") is also a way of exressing a Joule (unit
 *     of energy). However, torque is not energy. So, to avoid confusion, we
 *     will use the units "N·m" for torque and not "J". This distinction occurs
 *     due to the scalar nature of energy and the vector nature of torque.</p>
 * 
 * @author  <a href="mailto:jean-marie@dautelle.com">Jean-Marie Dautelle</a>
 * @version 1.0, January 14, 2006
 */
public interface Torque extends Quantity<Torque> {

    /**
     * Holds the SI unit (Système International d'Unités) for this quantity.
     */
    public final static Unit<Torque> UNIT = 
        new ProductUnit<Torque>(SI.NEWTON.times(SI.METER));

}