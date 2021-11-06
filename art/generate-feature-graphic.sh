#!/bin/bash

echo "Generating feature graphics to ~/tentel-icons/tentel-feature-graphic.png..."
mkdir -p ~/tentel-icons/
rsvg-convert feature-graphic.svg > ~/tentel-icons/feature-graphic.png
