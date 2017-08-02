#!/bin/bash
ps aux | grep service-1-0.0.1-SNAPSHOT.jar | awk '{print $2}' | xargs kill -9