import * as React from 'react';


import * as component from 'aem-react-js/ComponentManager';

import Text from './text/text';
import Accordion from './accordion/accordion';


component.ComponentManager.init({server:false});
let componentManager = component.ComponentManager.INSTANCE;

const comps:{ [name: string]: typeof React.Component } = {
  //insert your react component classes here!
  Text, Accordion
};
componentManager.setComponents(comps);

