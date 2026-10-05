#!/usr/bin/env python3
"""LiDio for Ubuntu (card b1768b44) – start: python3 main.py"""
import sys
from lidio.application import Application

if __name__ == "__main__":
    sys.exit(Application().run(sys.argv))
