/*
 * Copyright (c) 2026, btwinnn
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.chunkblazerui;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.Path2D;

/** Small colour and shape helpers shared by the card faces, flares and themes. */
final class Tints
{
	private Tints()
	{
	}

	/** The colour with its alpha replaced (clamped to 0-255). */
	static Color alpha(Color c, int a)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
	}

	/** Straight blend from a to b (opaque result). */
	static Color mix(Color a, Color b, double t)
	{
		return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	static Color brighter(Color c, double t)
	{
		return mix(c, Color.WHITE, t);
	}

	static Color darker(Color c, double t)
	{
		return mix(c, Color.BLACK, t);
	}

	/** A star with the given number of points; inner is the inner radius as a fraction of r. */
	static Shape star(double cx, double cy, double r, int points, double inner)
	{
		Path2D p = new Path2D.Double();
		for (int i = 0; i < points * 2; i++)
		{
			double a = -Math.PI / 2 + Math.PI * i / points;
			double rr = i % 2 == 0 ? r : r * inner;
			if (i == 0)
			{
				p.moveTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr);
			}
			else
			{
				p.lineTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr);
			}
		}
		p.closePath();
		return p;
	}
}
